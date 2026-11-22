package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{
  AuthRefusal, AuthRules, Credentials, EmailChange, EmailConfirmation,
  EmailStatus, PasswordChange, PasswordReset, PasswordResetRequest, Recovery,
  RecoveryCodes, User,
}
import java.security.SecureRandom
import java.util.Base64
import slick.dbio.DBIO

/**
  * A service for accounts and sign-in sessions: registration, guests, sign-in
  * and sign-out, passwords, recovery, email addresses and account deletion. It
  * also resolves session tokens to users for every request needing one.
  *
  * It knows nothing of HTTP: a request that opens or ends a session answers
  * with the [[SessionCookie]] to set. `hecate-server-tapir` serves each method
  * as an endpoint.
  *
  * @param users
  *   The store of users, sessions and recovery codes.
  *
  * @param policy
  *   The rules for sessions, passwords and mail.
  *
  * @param accounts
  *   The store that deletes accounts, or `None` to refuse deletion. Give one
  *   only once the host's cascade removes everything of a user's.
  *
  * @param mailing
  *   The composer of mail, or `None` to refuse every request about email
  *   addresses and forgotten passwords.
  *
  * @param report
  *   The handler of failures whose detail must not reach the client. The
  *   default prints to the console.
  *
  * @param affected
  *   The hook told whom each committed change concerns (see [[Affected]]).
  */
final class AuthService
  (
    users: UserStore,
    policy: AuthPolicy = AuthPolicy(),
    accounts: Option[AccountStore] = None,
    mailing: Option[Mailing] = None,
    report: Throwable => IO[Unit] = error => Console[IO].printStackTrace(error),
    affected: Affected => IO[Unit] = _ => IO.unit,
  ):

  private val failures = Failures(report)

  /**
    * The hash checked when there is no account, under the policy's count, so
    * that refusing an unknown username costs what a wrong password does.
    */
  private val decoy = Passwords.decoy(policy.hashIterations)

  /**
    * Registers an account and opens a session for it.
    *
    * @param credentials
    *   The username and password chosen.
    *
    * @return
    *   An answer with the user and the session's cookie, or a refusal.
    */
  def register(credentials: Credentials): Answer[(User, SessionCookie)] =
    failures.attemptRefusable(
      checked(policy.problem(credentials))(signUp(credentials)),
    )

  /**
    * Makes a guest, runs what they came for and opens their session, all at
    * once, so nothing is made if `arrival` refuses. Refused unless
    * [[AuthPolicy.guests]].
    *
    * @tparam X
    *   The type of what `arrival` produces.
    *
    * @param name
    *   The name the guest gave, which becomes their username, numbered if taken
    *   (see [[UserStore.createGuest]]).
    *
    * @param arrival
    *   The action the guest came for, run in the transaction that makes them,
    *   refusing by failing with an [[AuthProblem]].
    *
    * @return
    *   An answer with the guest, what `arrival` produced and the session's
    *   cookie, or a refusal.
    */
  def createGuest[X]
    (name: String)
    (arrival: User => DBIO[X])
    : Answer[(User, X, SessionCookie)] = failures.attemptRefusable(
    checked(
      Option.unless(policy.guests)(AuthRefusal.NoGuests),
      Bounds.guestName(name),
      Option.when(name.trim.isEmpty)(AuthRefusal.GuestNameEmpty),
    )(
      opening((token, expiresAt) =>
        users.createGuest(name.trim, token, expiresAt)(arrival),
      ).map { case ((made, arrived), cookie) => Right((made, arrived, cookie)) },
    ),
  )

  /**
    * Claims a guest's account with a username and password, keeping what is
    * theirs. Closes every session and opens a fresh one. Refused for an account
    * with a password.
    *
    * @param user
    *   The signed-in guest.
    *
    * @param credentials
    *   The username and password chosen.
    *
    * @return
    *   An answer with the claimed user and the session's cookie, or a refusal.
    */
  def claim
    (user: User, credentials: Credentials)
    : Answer[(User, SessionCookie)] = failures
    .attemptRefusable(checked(policy.problem(credentials))(
      hashed(credentials.password)
        .flatMap(hash =>
          opening(users.claim(
            user.id,
            credentials.username.trim,
            hash,
            _,
            _,
          )),
        )
        .map((claimed, cookie) => claimed.map(_ -> cookie)),
    ))
    .flatTap(claimed => tell(claimed.toOption.as(user.id), account))

  /**
    * Opens a session once the password matches. An unknown username is refused
    * exactly as a wrong password is, and as slowly. A suspended account is
    * refused as such only once its password is right.
    *
    * @param credentials
    *   The username and password given.
    *
    * @return
    *   An answer with the user and the session's cookie, or a refusal.
    */
  def signIn(credentials: Credentials): Answer[(User, SessionCookie)] = failures
    .attemptRefusable(
      checked(
        Bounds.name(credentials.username),
        Bounds.password(credentials.password),
      )(authenticate(credentials)),
    )

  /**
    * Closes a session.
    *
    * @param token
    *   The session's token, if the request carried one.
    *
    * @return
    *   An answer with the cookie clearing the session, or a refusal.
    */
  def signOut(token: Option[String]): Answer[SessionCookie] = failures.attempt(
    for
      user <- lookup(token)
      _    <- token.traverse_(users.closeSession)
      _    <- tell(user.map(_.id), account)
    yield SessionCookie.closed,
  )

  /**
    * Finds the user signed in under a session token.
    *
    * @param token
    *   The session's token, if the request carried one.
    *
    * @return
    *   An answer with the user, or `None` if nobody is signed in.
    */
  def current(token: Option[String]): Answer[Option[User]] =
    failures.attempt(lookup(token))

  /**
    * Finds the user signed in under a session token, refusing if there is none.
    *
    * @param token
    *   The session's token, if the request carried one.
    *
    * @return
    *   An answer with the user, or a refusal.
    */
  def signedIn(token: Option[String]): Answer[User] =
    current(token).map(_.flatMap(_.toRight(AuthRefusal.SignedOut)))

  /** The rules for accounts, for clients to state before refusing anything. */
  def rules: AuthRules = AuthRules(
    policy.minPasswordLength,
    accounts.isDefined,
    mailing.isDefined,
    policy.guests,
  )

  /**
    * Changes a user's password once their current one is confirmed, closing
    * every session and opening a fresh one.
    *
    * @param user
    *   The signed-in user.
    *
    * @param change
    *   The current password and its replacement.
    *
    * @return
    *   An answer with the new session's cookie, or a refusal.
    */
  def changePassword
    (user: User, change: PasswordChange)
    : Answer[SessionCookie] = failures
    .attemptRefusable(
      checked(
        Bounds.password(change.current),
        policy.passwordProblem(change.replacement),
      )(confirmed(user, change.current)(reset(user, change.replacement))),
    )
    .flatTap(changed => tell(changed.toOption.as(user.id), account))

  /**
    * Issues a fresh set of recovery codes once the password is confirmed,
    * invalidating earlier codes. The codes are answered only this once.
    *
    * @param user
    *   The signed-in user.
    *
    * @param password
    *   The user's password.
    *
    * @return
    *   An answer with the codes, or a refusal.
    */
  def generateRecoveryCodes
    (user: User, password: String)
    : Answer[RecoveryCodes] = failures
    .attemptRefusable(checked(Bounds.password(password))(
      confirmed(user, password)(issueCodes(user)),
    ))
    .flatTap(issued => tell(issued.toOption.as(user.id), account))

  /**
    * Counts a user's unused recovery codes.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the count, or a refusal.
    */
  def recoveryCodesLeft(user: User): Answer[Int] =
    failures.attempt(users.recoveryCodesLeft(user.id))

  /**
    * Regains an account with a recovery code: sets a new password, closes every
    * other session and opens one. Never says whether the username or the code
    * was wrong.
    *
    * @param recovery
    *   The username, code and new password.
    *
    * @return
    *   An answer with the user and the session's cookie, or a refusal.
    */
  def recover(recovery: Recovery): Answer[(User, SessionCookie)] = failures
    .attemptRefusable(
      checked(
        Bounds.name(recovery.username),
        Bounds.name(recovery.code),
        policy.passwordProblem(recovery.replacement),
      )(regain(recovery)),
    )
    .flatTap(regained =>
      tell(
        regained.toOption.map(_._1.id),
        account,
      ),
    )

  /**
    * Deletes a user's account (see [[AccountStore.delete]]) once their password
    * is confirmed; a guest's needs none. Refused if the service has no
    * [[AccountStore]], and while anyone manages the account, as deleting it is
    * then theirs alone ([[AuthRefusal.AccountManaged]]).
    *
    * @param user
    *   The signed-in user.
    *
    * @param password
    *   The user's password, ignored for a guest.
    *
    * @return
    *   An answer with the cookie clearing the session, or a refusal.
    */
  def deleteAccount(user: User, password: String): Answer[SessionCookie] =
    failures
      .attemptRefusable(
        checked(Bounds.password(password))(removeAccount(user, password)),
      )
      .flatTap(removed => tell(removed.toOption.as(user.id), deleted))

  /**
    * Sends a password reset link to every account with the given confirmed
    * address. It answers at once and alike whether or not any account has the
    * address, sending in the background, so that the answer discloses nothing;
    * an account sent a link too recently is skipped.
    *
    * @param resetRequest
    *   The address to send the links to.
    *
    * @param locale
    *   The language to write the mail in.
    */
  def requestPasswordReset
    (
      resetRequest: PasswordResetRequest,
      locale: Option[String],
    )
    : Answer[Unit] = failures.attemptRefusable(
    checked(EmailAddress.problem(resetRequest.address))(mailed(mailing =>
      sendResets(
        mailing,
        EmailAddress.normalise(resetRequest.address),
        locale,
      ).handleErrorWith(report).start.as(Right(())),
    )),
  )

  /**
    * Resets a forgotten password with a link sent by email: sets the new
    * password, closes every session and opens one. Every reset link of the
    * account then stops working.
    *
    * @param reset
    *   The link's secret and the new password.
    *
    * @return
    *   An answer with the user and the session's cookie, or a refusal.
    */
  def resetPassword(reset: PasswordReset): Answer[(User, SessionCookie)] =
    failures
      .attemptRefusable(
        checked(
          Bounds.name(reset.token),
          policy.passwordProblem(reset.replacement),
        )(resetByLink(reset)),
      )
      .flatTap(done => tell(done.toOption.map(_._1.id), account))

  /**
    * Reads a user's email address and any address awaiting confirmation.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the addresses, or a refusal.
    */
  def email(user: User): Answer[EmailStatus] = failures.attempt(status(user.id))

  /**
    * Changes a user's email address once their password is confirmed. A new
    * address is sent a link and replaces the old one only once confirmed;
    * giving the current address withdraws a pending change, and giving none
    * removes the address at once.
    *
    * @param user
    *   The signed-in user.
    *
    * @param change
    *   The new address, if any, and the user's password.
    *
    * @param locale
    *   The language to write the mail in.
    *
    * @return
    *   An answer with the addresses after the change, or a refusal.
    */
  def changeEmail
    (
      user: User,
      change: EmailChange,
      locale: Option[String],
    )
    : Answer[EmailStatus] = failures
    .attemptRefusable(
      checked(
        Bounds.password(change.password),
        change.address.flatMap(EmailAddress.problem),
      )(mailed(mailing =>
        vouched(user, change.password)(changeTo(
          mailing,
          user,
          change.address.map(EmailAddress.normalise),
          locale,
        )),
      )),
    )
    .flatTap(changed => tell(changed.toOption.as(user.id), account))

  /**
    * Sends the address awaiting a user's confirmation a fresh link, unless the
    * last was sent too recently. Changes nothing when no address awaits.
    *
    * @param user
    *   The signed-in user.
    *
    * @param locale
    *   The language to write the mail in.
    *
    * @return
    *   An answer with the user's addresses, or a refusal.
    */
  def resendConfirmation
    (user: User, locale: Option[String])
    : Answer[EmailStatus] = failures.attemptRefusable(mailed(mailing =>
    status(user.id).flatMap(found =>
      found
        .pending
        .fold[Answer[Unit]](IO.pure(Right(
          (),
        )))(sendConfirmation(mailing, user, _, locale))
        .map(_.as(found)),
    ),
  ))

  /**
    * Confirms an address with the link sent to it, making it its user's. Every
    * other link sent them stops working, and the old address is told of the
    * change.
    *
    * @param confirmation
    *   The link's secret.
    *
    * @param locale
    *   The language to write to the old address in.
    */
  def confirmEmail
    (
      confirmation: EmailConfirmation,
      locale: Option[String],
    )
    : Answer[Unit] = failures
    .attemptRefusable(checked(Bounds.name(confirmation.token))(
      now
        .flatMap(users.confirmByLink(Digest.of(confirmation.token), _))
        .flatTap(_.traverse_((user, previous) =>
          farewell(user, previous, locale),
        ))
        .map(_.map(_._1).toRight(AuthRefusal.EmailLinkMissing)),
    ))
    .flatTap(confirmed => tell(confirmed.toOption.map(_.id), account))
    .map(_.void)

  /** Tells the host whom a committed change concerns, reporting any failure. */
  private def tell
    (
      user: Option[Long],
      concerned: Long => List[Affected],
    )
    : IO[Unit] = user
    .toList
    .flatMap(concerned)
    .traverse_(affected)
    .handleErrorWith(report)

  private def account(user: Long): List[Affected] = List(Affected.Account(user))

  /** Whom an account's deletion concerns: everyone, as it leaves its groups. */
  private def deleted(user: Long): List[Affected] = List(
    Affected.Account(user),
    Affected.Groups(Audience.Everyone, Set.empty),
  )

  private def signUp(credentials: Credentials): Answer[(User, SessionCookie)] =
    hashed(credentials.password)
      .flatMap(users.register(credentials.username.trim, _))
      .flatMap:
        case None       => IO.pure(Left(AuthRefusal.UsernameTaken))
        case Some(user) => openSession(user).map(Right(_))

  private def authenticate
    (credentials: Credentials)
    : Answer[(User, SessionCookie)] = users
    .findByUsername(credentials.username.trim)
    .flatMap(verified(_, credentials.password))
    .flatMap:
      case None      => IO.pure(Left(AuthRefusal.CredentialsIncorrect))
      case Some(row) => users
          .suspended(row.id)
          .ifM(
            IO.pure(Left(AuthRefusal.Suspended)),
            openSession(row.toUser).map(Right(_)),
          )

  private def lookup(token: Option[String]): IO[Option[User]] = token
    .flatTraverse(users.sessionUser)

  /** Stores a new password, closing every session and opening a fresh one. */
  private def reset(user: User, replacement: String): IO[SessionCookie] =
    hashed(replacement)
      .flatMap(hash => opening(users.resetPassword(user.id, hash, _, _)))
      .map(_._2)

  private def removeAccount
    (user: User, password: String)
    : Answer[SessionCookie] = accounts match
    case None        => IO.pure(Left(AuthRefusal.NoAccountDeletion))
    case Some(store) => users
        .find(user.id)
        .flatMap:
          case Some(row) if row.passwordHash.isEmpty =>
            store.deleteOwn(user.id).as(Right(SessionCookie.closed))
          case _ => confirmed(user, password):
              store.deleteOwn(user.id).as(SessionCookie.closed)

  private def issueCodes(user: User): IO[RecoveryCodes] = RecoveryCode
    .generate
    .flatTap(codes =>
      users.replaceRecoveryCodes(user.id, codes.map(RecoveryCode.hash)),
    )
    .map(RecoveryCodes(_))

  /**
    * Regains an account with a recovery code. The password is hashed before the
    * lookup, so the answer takes as long whether or not the account exists.
    */
  private def regain(recovery: Recovery): Answer[(User, SessionCookie)] =
    hashed(recovery.replacement)
      .flatMap(hash =>
        opening(users.recover(
          recovery.username.trim,
          RecoveryCode.hash(recovery.code),
          hash,
          _,
          _,
        )),
      )
      .map((found, cookie) =>
        found.map(_ -> cookie).toRight(AuthRefusal.RecoveryIncorrect),
      )

  /** Runs an action once the user has given their password again. */
  private def confirmed[X]
    (user: User, password: String)
    (action: => IO[X])
    : Answer[X] = vouched(user, password)(action.map(Right(_)))

  /** As [[confirmed]], for an action that may refuse itself. */
  private def vouched[X]
    (user: User, password: String)
    (answer: => Answer[X])
    : Answer[X] = users
    .find(user.id)
    .flatMap(verified(_, password))
    .flatMap:
      case Some(_) => answer
      case None    => IO.pure(Left(AuthRefusal.PasswordIncorrect))

  private def mailed[X](answer: Mailing => Answer[X]): Answer[X] =
    mailing.fold[Answer[X]](IO.pure(Left(AuthRefusal.NoEmail)))(answer)

  private def status(user: Long): IO[EmailStatus] =
    now.flatMap(users.emailStatus(user, _))

  private def changeTo
    (
      mailing: Mailing,
      user: User,
      address: Option[String],
      locale: Option[String],
    )
    : Answer[EmailStatus] = address match
    case None => users
        .removeEmail(user.id)
        .flatMap(farewell(user, _, locale))
        .productR(status(user.id).map(Right(_)))
    case Some(to) => status(user.id).flatMap(found =>
        if found.address.contains(to) then
          users.withdrawConfirmation(user.id) *> status(user.id).map(Right(_))
        else
          sendConfirmation(mailing, user, to, locale).flatMap(
            _.traverse(_ => status(user.id)),
          ),
      )

  /**
    * Tells a former address it is no longer the user's, in case someone else
    * changed it, reporting rather than failing if the mail cannot be sent.
    */
  private def farewell
    (
      user: User,
      address: Option[String],
      locale: Option[String],
    )
    : IO[Unit] = mailing
    .zip(address)
    .traverse_((via, to) =>
      via.mailer.send(via.addressChangedMail(to, user.username, locale)),
    )
    .handleErrorWith(report)

  private def sendConfirmation
    (
      mailing: Mailing,
      user: User,
      address: String,
      locale: Option[String],
    )
    : Answer[Unit] = sendLink(
    mailing,
    user,
    EmailPurpose.Confirm,
    address,
    policy.confirmHours,
  )(mailing.confirmMail(address, _, policy.confirmHours, locale))

  private def sendResets
    (
      mailing: Mailing,
      address: String,
      locale: Option[String],
    )
    : IO[Unit] = users
    .withEmail(address)
    .flatMap(_.traverse_(user =>
      sendLink(
        mailing,
        user,
        EmailPurpose.Reset,
        address,
        policy.resetHours,
      )(mailing.resetMail(
        address,
        user.username,
        _,
        policy.resetHours,
        locale,
      )),
    ))

  /**
    * Stores a fresh link and mails it, unless one of the same purpose was sent
    * too recently. A mail that fails to send withdraws its link, so it does not
    * count as sent.
    */
  private def sendLink
    (
      mailing: Mailing,
      user: User,
      purpose: EmailPurpose,
      address: String,
      hours: Int,
    )
    (compose: String => Mail)
    : Answer[Unit] =
    for
      token  <- AuthService.freshToken
      sentAt <- now
      link = EmailLinkRow(
        Digest.of(token),
        user.id,
        purpose.code,
        address,
        sentAt,
        sentAt + hours * AuthPolicy.hourMillis,
      )
      issued <- users.issueLink(
        link,
        sentAt - policy.mailIntervalMillis,
        policy.addressMailsPerHour,
      )
      answer <-
        if issued then
          mailing
            .mailer
            .send(compose(token))
            .onError { case _ => users.withdrawLink(link.tokenHash) }
            .as(Right(()))
        else IO.pure(Left(AuthRefusal.MailedRecently))
    yield answer

  /**
    * Resets a password with an emailed link. The password is hashed before the
    * lookup, so the answer takes as long whether or not the link works.
    */
  private def resetByLink(reset: PasswordReset): Answer[(User, SessionCookie)] =
    for
      hash            <- hashed(reset.replacement)
      at              <- now
      (found, cookie) <-
        opening(users.resetByLink(Digest.of(reset.token), hash, _, _, at))
    yield found.map(_ -> cookie).toRight(AuthRefusal.EmailLinkMissing)

  /** The time now, in milliseconds since the epoch. */
  private def now: IO[Long] = IO.realTime.map(_.toMillis)

  /**
    * The account, if the password matches its hash. A missing account or a
    * guest's is checked against the [[decoy]] and never matches, so timing
    * discloses neither.
    */
  private def verified
    (found: Option[UserRow], password: String)
    : IO[Option[UserRow]] =
    val hash = found.flatMap(_.passwordHash)
    Passwords
      .verify(password, hash.getOrElse(decoy))
      .map(matches => found.filter(_ => matches && hash.isDefined))
      .flatTap(_.traverse_(rehashed(_, password)))

  /** Hashes a proven password again if it was stored under fewer iterations. */
  private def rehashed(row: UserRow, password: String): IO[Unit] = IO.whenA(
    row.passwordHash.exists(Passwords.outdated(_, policy.hashIterations)),
  ):
    hashed(password).flatMap(users.rehash(row.id, _))

  private def hashed(password: String): IO[String] =
    Passwords.hash(password, policy.hashIterations)

  private def openSession(user: User): IO[(User, SessionCookie)] =
    opening(users.openSession(_, user.id, _)).map((_, cookie) => (user, cookie))

  private def opening[X]
    (open: (String, Long) => IO[X])
    : IO[(X, SessionCookie)] =
    for
      token     <- AuthService.freshToken
      expiresAt <- expiry
      opened    <- open(token, expiresAt)
    yield (opened, SessionCookie(token, policy.sessionSeconds))

  /** The expiry of a session opened now, in milliseconds since the epoch. */
  private def expiry: IO[Long] = IO
    .realTime
    .map(_.toMillis + policy.sessionMillis)

object AuthService:

  private val random = SecureRandom()

  private val freshToken: IO[String] = IO:
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

/**
  * A host's rules for sessions, passwords and mail.
  *
  * @param sessionSeconds
  *   The lifetime of a session, enforced in the cookie and the store.
  *
  * @param minPasswordLength
  *   The fewest characters a password may have.
  *
  * @param hashIterations
  *   The PBKDF2 iteration count for new passwords. A password stored under
  *   fewer is hashed again when next proven; one stored under more is left as
  *   it is.
  *
  * @param resetHours
  *   The number of hours a password reset link works for.
  *
  * @param confirmHours
  *   The number of hours an address confirmation link works for.
  *
  * @param mailIntervalSeconds
  *   The fewest seconds between two mails of the same kind to one account; at
  *   most a day, as sent mails are recorded no longer.
  *
  * @param addressMailsPerHour
  *   The most mails with links sent to one address in an hour, across all
  *   accounts.
  *
  * @param guests
  *   Whether someone not signed in may follow an invite link as a guest (see
  *   [[AuthService.createGuest]]). A guest who does not claim their account
  *   cannot reach it once their session ends.
  */
final case class AuthPolicy
  (
    sessionSeconds: Long = 30L * 24 * 60 * 60,
    minPasswordLength: Int = 8,
    hashIterations: Int = Passwords.defaultIterations,
    resetHours: Int = 1,
    confirmHours: Int = 24,
    mailIntervalSeconds: Long = 60,
    addressMailsPerHour: Int = 5,
    guests: Boolean = false,
  ):

  /** The lifetime of a session, in milliseconds. */
  def sessionMillis: Long = sessionSeconds * 1000

  /** The fewest milliseconds between two mails of the same kind to one account. */
  def mailIntervalMillis: Long = mailIntervalSeconds * 1000

  /**
    * Checks credentials for a new account.
    *
    * @param credentials
    *   The username and password chosen.
    *
    * @return
    *   A refusal if the credentials are unusable, or `None`.
    */
  def problem(credentials: Credentials): Option[AuthRefusal] = Bounds
    .name(credentials.username)
    .orElse(
      Option.when(credentials.username.trim.isEmpty)(AuthRefusal.UsernameEmpty),
    )
    .orElse(passwordProblem(credentials.password))

  /**
    * Checks a new password.
    *
    * @param password
    *   The password chosen.
    *
    * @return
    *   A refusal if the password is unusable, or `None`.
    */
  def passwordProblem(password: String): Option[AuthRefusal] = Bounds
    .password(password)
    .orElse(Option.when(password.length < minPasswordLength)(
      AuthRefusal.PasswordTooShort(minPasswordLength),
    ))

object AuthPolicy:

  /** The number of milliseconds in an hour. */
  val hourMillis: Long = 60L * 60 * 1000
