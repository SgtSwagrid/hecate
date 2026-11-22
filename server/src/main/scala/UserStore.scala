package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{AuthRefusal, EmailStatus, User}
import java.sql.SQLException
import scala.util.{Failure, Success}

/**
  * A store of users, registered and guests, with their sessions, recovery
  * codes, email addresses and emailed links.
  *
  * Usernames are stored as chosen but matched whatever their letter case
  * ([[Username.key]]), so `Alice` signs in as `alice` and neither can be taken
  * once the other is. Sessions, codes and links are stored only as hashes of
  * their secrets.
  *
  * @param tables
  *   The tables the users are stored in.
  *
  * @param db
  *   The database to run queries against.
  */
final class UserStore(tables: AuthTables, db: Transactor):

  import tables.profile.api.*

  /**
    * Stores a new user. The username's key column is unique, so the insert
    * alone decides whether the name is free in any case; a check first would
    * race.
    *
    * @param username
    *   The username, already trimmed.
    *
    * @param passwordHash
    *   The password's hash, as [[Passwords.hash]] makes it.
    *
    * @return
    *   An effect producing the user, or `None` if the username is taken.
    */
  def register(username: String, passwordHash: String): IO[Option[User]] = db
    .run(
      (tables.users.returning(tables.users.map(_.id)) +=
        UserRow(0, username, Some(passwordHash), None))
        .asTry
        .flatMap:
          case Success(id) => DBIO.successful(Some(User(id, username)))
          case Failure(error) if UserStore.violatesConstraint(error) =>
            DBIO.successful(None)
          case Failure(error) => DBIO.failed(error),
    )

  /**
    * Makes a guest, runs `arrival` and opens the guest's session, all in one
    * transaction, so nothing is made if `arrival` fails.
    *
    * The username is the name if free, or else the name with the lowest free
    * number (`Sam 2`, `Sam 3`, ...). Guests of one name made at once may race
    * for a number; the loser's transaction is retried a few times.
    *
    * @tparam X
    *   The type of what `arrival` produces.
    *
    * @param name
    *   The name the guest gave, trimmed.
    *
    * @param token
    *   The token of the guest's session.
    *
    * @param expiresAt
    *   The session's expiry, in milliseconds since the epoch.
    *
    * @param arrival
    *   The action the guest was made for, run in the same transaction.
    *
    * @return
    *   An effect producing the guest and what `arrival` produced.
    */
  def createGuest[X]
    (
      name: String,
      token: String,
      expiresAt: Long,
    )
    (arrival: User => DBIO[X])
    : IO[(User, X)] =
    val action = (for
      taken <- tables
        .users
        .filter(row =>
          row.usernameKey === Username.key(name) ||
          row.usernameKey.startsWith(s"${ Username.key(name) } "),
        )
        .map(_.usernameKey)
        .result
      username = UserStore.numbered(name, taken.toSet)
      id <- tables.users.returning(tables.users.map(_.id)) +=
        UserRow(0, username, None, None)
      guest = User(id, username, guest = true)
      arrived <- arrival(guest)
      _       <- tables.sessions += SessionRow(Digest.of(token), id, expiresAt)
    yield guest -> arrived).transactionally
    def withRetries(left: Int): IO[(User, X)] = db
      .run(action)
      .handleErrorWith:
        case error if left > 1 && UserStore.violatesConstraint(error) =>
          withRetries(left - 1)
        case error => IO.raiseError(error)
    withRetries(UserStore.guestTries)

  /**
    * Claims a guest's account with a username and password, closing its
    * sessions and opening the given one. The user's row is locked, so two
    * claims cannot both find a guest; the unique username key column decides
    * whether the name is free in any case.
    *
    * @param user
    *   The identifier of the guest.
    *
    * @param username
    *   The username chosen, already trimmed.
    *
    * @param passwordHash
    *   The password's hash.
    *
    * @param token
    *   The token of the new session.
    *
    * @param expiresAt
    *   The session's expiry, in milliseconds since the epoch.
    *
    * @return
    *   An effect producing the claimed user, or [[AuthRefusal.NotGuest]] for an
    *   account with a password or none at all, or
    *   [[AuthRefusal.UsernameTaken]].
    */
  def claim
    (
      user: Long,
      username: String,
      passwordHash: String,
      token: String,
      expiresAt: Long,
    )
    : IO[Either[AuthRefusal, User]] = db.run(
    (for
      held <- tables.lockUser(user)
      _    <- refuseUnless(
        held.exists(_.passwordHash.isEmpty),
        AuthRefusal.NotGuest,
      )
      _ <- byId(user)
        .map(row => (row.username, row.usernameKey))
        .update((username, Username.key(username)))
      _ <- reset(user, passwordHash, token, expiresAt)
    yield User(user, username))
      .transactionally
      .asTry
      .flatMap:
        case Success(claimed)              => DBIO.successful(Right(claimed))
        case Failure(AuthProblem(refusal)) => DBIO.successful(Left(refusal))
        case Failure(error) if UserStore.violatesConstraint(error) =>
          DBIO.successful(Left(AuthRefusal.UsernameTaken))
        case Failure(error) => DBIO.failed(error),
  )

  /**
    * Finds a user by username, whatever its letter case.
    *
    * @param username
    *   The username, already trimmed.
    *
    * @return
    *   An effect producing the user's row, password hash included, or `None`.
    */
  def findByUsername(username: String): IO[Option[UserRow]] =
    db.run(byUsername(username).result.headOption)

  /**
    * Finds which of some usernames are held, whatever their letter case.
    *
    * @param usernames
    *   The usernames, already trimmed.
    *
    * @return
    *   An effect producing those held, as given.
    */
  def heldAmong(usernames: Seq[String]): IO[List[String]] = db
    .run(
      ifAny(usernames.map(Username.key).distinct)(Seq.empty[String]): keys =>
        tables.users.filter(_.usernameKey inSet keys).map(_.usernameKey).result,
    )
    .map(held =>
      usernames.filter(name => held.contains(Username.key(name))).toList,
    )

  /**
    * Finds users by identifier, with no check of who may see whom: the host
    * must decide that first, and never expose this directly.
    *
    * @param ids
    *   The identifiers of the users.
    *
    * @return
    *   An effect producing each found user keyed by identifier.
    */
  def byIds(ids: Seq[Long]): IO[Map[Long, User]] = db.run(
    ifAny(ids.distinct)(Map.empty)(found =>
      tables
        .users
        .filter(_.id inSet found)
        .result
        .map(_.map(row => row.id -> row.toUser).toMap),
    ),
  )

  /**
    * Finds a user by identifier.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the user's row, password hash included, or `None`.
    */
  def find(user: Long): IO[Option[UserRow]] =
    db.run(byId(user).result.headOption)

  /**
    * Opens a sign-in session.
    *
    * @param token
    *   The session's secret token, stored only as its hash.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param expiresAt
    *   The session's expiry, in milliseconds since the epoch.
    */
  def openSession(token: String, user: Long, expiresAt: Long): IO[Unit] = db
    .run(tables.sessions += SessionRow(Digest.of(token), user, expiresAt))
    .void

  /**
    * Deletes every expired session and emailed link, and every record of a mail
    * sent a day ago or more. Nothing in the library calls this: expired rows
    * are refused anyway, so the host schedules it as housekeeping.
    */
  def purgeExpired: IO[Unit] = IO
    .realTime
    .flatMap(now =>
      db.run(DBIO.seq(
        tables.sessions.filter(_.expiresAt <= now.toMillis).delete,
        tables.emailLinks.filter(_.expiresAt <= now.toMillis).delete,
        tables
          .sentMails
          .filter(_.sentAt <= now.toMillis - UserStore.dayMillis)
          .delete,
      )),
    )

  /**
    * Finds the user signed in under a session token.
    *
    * @param token
    *   The session's token.
    *
    * @return
    *   An effect producing the user, or `None` if the session is missing or
    *   expired, or the account is suspended or has a password someone else set,
    *   whenever its session was opened.
    */
  def sessionUser(token: String): IO[Option[User]] = IO
    .realTime
    .flatMap(now =>
      db.run(
        tables
          .sessions
          .filter(session =>
            session.tokenHash === Digest.of(token) &&
            session.expiresAt > now.toMillis,
          )
          .join(tables.users)
          .on(_.userId === _.id)
          .map(_._2)
          .filterNot(user =>
            tables.suspensions.filter(_.userId === user.id).exists ||
            tables.temporaryPasswords.filter(_.userId === user.id).exists,
          )
          .result
          .headOption,
      ),
    )
    .map(_.map(_.toUser))

  /**
    * Closes a session.
    *
    * @param token
    *   The session's token.
    */
  def closeSession(token: String): IO[Unit] = db
    .run(tables.sessions.filter(_.tokenHash === Digest.of(token)).delete)
    .void

  /**
    * Replaces a user's password, closing every session and emailed link and
    * opening the given session.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param passwordHash
    *   The new password's hash.
    *
    * @param token
    *   The token of the new session.
    *
    * @param expiresAt
    *   The session's expiry, in milliseconds since the epoch.
    */
  def resetPassword
    (
      user: Long,
      passwordHash: String,
      token: String,
      expiresAt: Long,
    )
    : IO[Unit] =
    db.run(reset(user, passwordHash, token, expiresAt).transactionally)

  /**
    * Regains an account with a recovery code, using the code up and resetting
    * the password as [[resetPassword]] does. A missing account and a wrong code
    * are answered alike, so usernames cannot be discovered. A suspended account
    * fails with [[AuthRefusal.Suspended]], once the code is right, keeping it.
    *
    * @param username
    *   The account's username.
    *
    * @param codeHash
    *   The code's hash, as [[RecoveryCode.hash]] makes it.
    *
    * @param passwordHash
    *   The new password's hash.
    *
    * @param token
    *   The token of the new session.
    *
    * @param expiresAt
    *   The session's expiry, in milliseconds since the epoch.
    *
    * @return
    *   An effect producing the user, or `None`, changing nothing.
    */
  def recover
    (
      username: String,
      codeHash: String,
      passwordHash: String,
      token: String,
      expiresAt: Long,
    )
    : IO[Option[User]] = db.run(
    byUsername(username)
      .result
      .headOption
      .flatMap(regain(
        _,
        codeHash,
        passwordHash,
        token,
        expiresAt,
      ))
      .transactionally,
  )

  private def regain
    (
      found: Option[UserRow],
      codeHash: String,
      passwordHash: String,
      token: String,
      expiresAt: Long,
    )
    : DBIO[Option[User]] = found.fold(DBIO.successful(None))(row =>
    consumeCode(row.id, codeHash).flatMap(used =>
      if used then
        for
          _ <- unlessSuspended(row.id)
          _ <- reset(row.id, passwordHash, token, expiresAt)
        yield Some(row.toUser)
      else DBIO.successful(None),
    ),
  )

  /**
    * Uses up a recovery code. Deleting it checks and consumes it in one step,
    * so two racing recoveries cannot both succeed.
    */
  private def consumeCode(user: Long, codeHash: String): DBIO[Boolean] =
    codeOf(user, codeHash).delete.map(_ == 1)

  /**
    * Replaces every recovery code of a user.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param codeHashes
    *   The new codes' hashes.
    */
  def replaceRecoveryCodes(user: Long, codeHashes: Seq[String]): IO[Unit] = db
    .run(
      DBIO
        .seq(
          codesOf(user).delete,
          tables.recoveryCodes ++= codeHashes.map(RecoveryCodeRow(0, user, _)),
        )
        .transactionally,
    )

  /**
    * Counts a user's unused recovery codes.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the count.
    */
  def recoveryCodesLeft(user: Long): IO[Int] =
    db.run(codesOf(user).length.result)

  /** Replaces a password hash with one of more iterations, keeping sessions. */
  private[server] def rehash(user: Long, passwordHash: String): IO[Unit] = db
    .run(byId(user).map(_.passwordHash).update(Some(passwordHash)))
    .void

  /**
    * Finds the users with a confirmed address.
    *
    * @param address
    *   The address, normalised.
    *
    * @return
    *   An effect producing the users.
    */
  def withEmail(address: String): IO[Seq[User]] = db
    .run(tables.users.filter(_.email === address).result)
    .map(_.map(_.toUser))

  /**
    * Reads a user's confirmed address and any address awaiting confirmation.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param now
    *   The current time, in milliseconds since the epoch.
    *
    * @return
    *   An effect producing the addresses.
    */
  def emailStatus(user: Long, now: Long): IO[EmailStatus] = db.run(
    for
      address <- byId(user).map(_.email).result.headOption
      pending <- linksOf(user, EmailPurpose.Confirm)
        .filter(_.expiresAt > now)
        .map(_.address)
        .result
        .headOption
    yield EmailStatus(address.flatten, pending),
  )

  /**
    * Stores a link about to be mailed and records the mail as sent, unless: a
    * mail of the same purpose was sent to the user after `since`; `perAddress`
    * or more mails went to the address in the past hour; a reset is for an
    * address no longer the user's; or the user no longer exists.
    *
    * A confirmation replaces earlier confirmations; a reset replaces nothing,
    * so a link already mailed keeps working. The mail counts as sent whether or
    * not it is delivered. The user's row is locked, so two requests for one
    * user cannot both pass; requests for different users may still overshoot
    * one address's limit by how many arrive at once.
    *
    * @param link
    *   The link to store.
    *
    * @param since
    *   The time after which an earlier mail of the same purpose blocks this
    *   one, in milliseconds since the epoch.
    *
    * @param perAddress
    *   The most mails one address may receive in an hour.
    *
    * @return
    *   An effect producing whether the link was stored and may be sent.
    */
  def issueLink
    (
      link: EmailLinkRow,
      since: Long,
      perAddress: Int,
    )
    : IO[Boolean] = db.run(
    (
      for
        held   <- tables.lockUser(link.userId)
        toUser <- tables
          .sentMails
          .filter(mail =>
            mail.userId === link.userId && mail.purpose === link.purpose &&
            mail.sentAt > since,
          )
          .exists
          .result
        toAddress <- tables
          .sentMails
          .filter(mail =>
            mail.address === link.address &&
            mail.sentAt > link.sentAt - AuthPolicy.hourMillis,
          )
          .length
          .result
        issued = held.exists(mayReceive(_, link)) && !toUser &&
          toAddress < perAddress
        _ <-
          if issued then
            DBIO.seq(
              deleteReplaced(link),
              tables.emailLinks += link,
              tables.sentMails += SentMailRow(
                0,
                link.userId,
                link.purpose,
                link.address,
                link.sentAt,
              ),
            )
          else DBIO.unit
      yield issued
    ).transactionally,
  )

  /**
    * Deletes a link whose mail could not be sent. The mail still counts as sent
    * (see [[issueLink]]).
    *
    * @param tokenHash
    *   The hash of the link's secret.
    */
  def withdrawLink(tokenHash: String): IO[Unit] = db
    .run(tables.emailLinks.filter(_.tokenHash === tokenHash).delete)
    .void

  /**
    * Resets a password with an emailed link, using the link up and setting the
    * password as [[resetPassword]] does. A suspended account fails with
    * [[AuthRefusal.Suspended]], once the link is right, keeping it.
    *
    * @param tokenHash
    *   The hash of the link's secret.
    *
    * @param passwordHash
    *   The new password's hash.
    *
    * @param token
    *   The token of the new session.
    *
    * @param expiresAt
    *   The session's expiry, in milliseconds since the epoch.
    *
    * @param now
    *   The current time, in milliseconds since the epoch.
    *
    * @return
    *   An effect producing the user, or `None`, changing nothing, if the link
    *   does not work.
    */
  def resetByLink
    (
      tokenHash: String,
      passwordHash: String,
      token: String,
      expiresAt: Long,
      now: Long,
    )
    : IO[Option[User]] = db.run((for
    used <- consumeLink(tokenHash, EmailPurpose.Reset, now)
    _    <- used.fold[DBIO[Unit]](DBIO.unit)((_, row) =>
      unlessSuspended(row.id).flatMap(_ =>
        reset(row.id, passwordHash, token, expiresAt),
      ),
    )
  yield used.map(_._2.toUser)).transactionally)

  /**
    * Confirms an address with the link sent to it, using the link up, making
    * the address its user's and withdrawing every other link sent them, so a
    * reset link mailed to the old address stops working.
    *
    * @param tokenHash
    *   The hash of the link's secret.
    *
    * @param now
    *   The current time, in milliseconds since the epoch.
    *
    * @return
    *   An effect producing the user and the address replaced, if any, or
    *   `None`, changing nothing, if the link does not work.
    */
  def confirmByLink
    (tokenHash: String, now: Long)
    : IO[Option[(User, Option[String])]] = db.run(
    (for
      used <- consumeLink(tokenHash, EmailPurpose.Confirm, now)
      _    <- used.fold[DBIO[Unit]](DBIO.unit)((link, row) =>
        DBIO.seq(
          byId(row.id).map(_.email).update(Some(link.address)),
          allLinksOf(row.id).delete,
        ),
      )
    yield used.map((link, row) =>
      (row.toUser, row.email.filterNot(_ == link.address)),
    )).transactionally,
  )

  /**
    * Removes a user's address and withdraws every link sent them.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the address removed, if any.
    */
  def removeEmail(user: Long): IO[Option[String]] = db.run((for
    held <- tables.lockUser(user)
    _    <- DBIO.seq(
      byId(user).map(_.email).update(None),
      allLinksOf(user).delete,
    )
  yield held.flatMap(_.email)).transactionally)

  /**
    * Withdraws a user's pending address confirmation.
    *
    * @param user
    *   The identifier of the user.
    */
  def withdrawConfirmation(user: Long): IO[Unit] = db
    .run(linksOf(user, EmailPurpose.Confirm).delete)
    .void

  /**
    * Deletes a user's sessions, codes, links and row, for an account's
    * deletion. Records of mails sent stay for a day, so deleting accounts
    * cannot lift an address's mail limit.
    */
  private[server] def delete(user: Long): DBIO[Unit] = DBIO.seq(
    sessionsOf(user).delete,
    codesOf(user).delete,
    allLinksOf(user).delete,
    suspension(user).delete,
    temporary(user).delete,
    byId(user).delete,
  )

  /**
    * Checks whether an account is suspended.
    *
    * @param user
    *   The identifier of the account's user.
    *
    * @return
    *   An effect producing whether it is suspended.
    */
  def suspended(user: Long): IO[Boolean] =
    db.run(suspension(user).exists.result)

  /**
    * Checks whether an account has a password someone else set, which its user
    * must replace before signing in.
    *
    * @param user
    *   The identifier of the account's user.
    *
    * @return
    *   An effect producing whether it has.
    */
  def temporarilyPassworded(user: Long): IO[Boolean] =
    db.run(temporary(user).exists.result)

  /**
    * The accounts among some users with a password someone else set, in the
    * caller's transaction.
    */
  private[server] def temporaryAmong(users: Seq[Long]): DBIO[Set[Long]] = ifAny(
    users.distinct,
  )(Set.empty[Long]): wanted =>
    tables
      .temporaryPasswords
      .filter(_.userId inSet wanted)
      .map(_.userId)
      .result
      .map(_.toSet)

  /** The suspended accounts among some users, in the caller's transaction. */
  private[server] def suspendedAmong(users: Seq[Long]): DBIO[Set[Long]] = ifAny(
    users.distinct,
  )(Set.empty[Long]): wanted =>
    tables
      .suspensions
      .filter(_.userId inSet wanted)
      .map(_.userId)
      .result
      .map(_.toSet)

  /**
    * Suspends an account, closing every session of it, or lifts its suspension.
    * Callers must hold the user's row locked.
    */
  private[server] def suspend(user: Long, suspended: Boolean): DBIO[Unit] =
    if suspended then
      DBIO.seq(
        suspension(user).delete,
        tables.suspensions += SuspensionRow(user),
        sessionsOf(user).delete,
      )
    else suspension(user).delete.unit

  /**
    * Renames a user. The unique key column decides whether the name is free, so
    * the caller's transaction fails with a constraint violation (see
    * [[UserStore.taken]]) if it is not.
    */
  private[server] def rename(user: Long, username: String): DBIO[Unit] =
    byId(user)
      .map(row => (row.username, row.usernameKey))
      .update((username, Username.key(username)))
      .unit

  /**
    * Sets a user's password for them, closing every session and emailed link,
    * as no old session or link should outlive it, but keeping their recovery
    * codes, which are theirs. The password is temporary: its user must replace
    * it before signing in.
    */
  private[server] def setPassword
    (
      user: Long,
      passwordHash: String,
      setter: Long,
      at: Long,
    )
    : DBIO[Unit] = DBIO.seq(
    byId(user).map(_.passwordHash).update(Some(passwordHash)),
    sessionsOf(user).delete,
    allLinksOf(user).delete,
    temporary(user).delete,
    tables.temporaryPasswords += TemporaryPasswordRow(user, setter, at),
  )

  /**
    * Stores new users with their passwords' hashes, in order, each password
    * temporary as someone else set it. Fails with
    * [[AuthRefusal.UsernamesTaken]] naming each username already held; one
    * taken meanwhile fails with a constraint violation instead.
    */
  private[server] def createAll
    (
      accounts: Seq[(String, String)],
      setter: Long,
      at: Long,
    )
    : DBIO[Seq[User]] =
    val keys = accounts.map((username, _) => Username.key(username))
    for
      held <- ifAny(keys)(Seq.empty[String]): wanted =>
        tables
          .users
          .filter(_.usernameKey inSet wanted)
          .map(_.usernameKey)
          .result
      _ <- refuseUnless(
        held.isEmpty,
        AuthRefusal.UsernamesTaken(
          accounts
            .map(_._1)
            .filter(name => held.contains(Username.key(name)))
            .toList,
        ),
      )
      ids <- tables.users.returning(tables.users.map(_.id)) ++=
        accounts.map((username, hash) => UserRow(0, username, Some(hash), None))
      _ <- tables.temporaryPasswords ++=
        ids.map(TemporaryPasswordRow(_, setter, at))
    yield ids.zip(accounts).map((id, account) => User(id, account._1))

  /** Fails with [[AuthRefusal.Suspended]] if the account is suspended. */
  private def unlessSuspended(user: Long): DBIO[Unit] = suspension(user)
    .exists
    .result
    .flatMap(held => refuseUnless(!held, AuthRefusal.Suspended))

  /**
    * Sets a password of the user's own, replacing every session with the given
    * one and withdrawing every emailed link, so no old reset or confirmation
    * link can undo the change.
    */
  private def reset
    (
      user: Long,
      passwordHash: String,
      token: String,
      expiresAt: Long,
    )
    : DBIO[Unit] = DBIO.seq(
    byId(user).map(_.passwordHash).update(Some(passwordHash)),
    sessionsOf(user).delete,
    allLinksOf(user).delete,
    temporary(user).delete,
    tables.sessions += SessionRow(Digest.of(token), user, expiresAt),
  )

  /** Whether a link may go to the user: a reset only to their current address. */
  private def mayReceive(row: UserRow, link: EmailLinkRow): Boolean =
    link.purpose != EmailPurpose.Reset.code || row.email.contains(link.address)

  private def deleteReplaced(link: EmailLinkRow): DBIO[Unit] =
    if link.purpose == EmailPurpose.Confirm.code then
      linksOf(link.userId, EmailPurpose.Confirm).delete.unit
    else DBIO.unit

  /**
    * Uses up a working link of the given purpose. Deleting it checks and
    * consumes it in one step, so two racing requests cannot both succeed. The
    * user's row is locked before the link, the order [[issueLink]] and
    * [[removeEmail]] take, so they cannot deadlock.
    */
  private def consumeLink
    (
      tokenHash: String,
      purpose: EmailPurpose,
      now: Long,
    )
    : DBIO[Option[(EmailLinkRow, UserRow)]] =
    val link = tables.emailLinks.filter(_.tokenHash === tokenHash)
    link
      .filter(row => row.purpose === purpose.code && row.expiresAt > now)
      .result
      .headOption
      .flatMap(
        _.fold[DBIO[Option[(EmailLinkRow, UserRow)]]](DBIO.successful(None))(
          row =>
            tables
              .lockUser(row.userId)
              .flatMap(owner =>
                link
                  .delete
                  .map(deleted => Option.when(deleted == 1)(row).zip(owner)),
              ),
        ),
      )

  private def byId(user: Long) = tables.users.filter(_.id === user)

  private def temporary(user: Long) = tables
    .temporaryPasswords
    .filter(_.userId === user)

  private def suspension(user: Long) = tables
    .suspensions
    .filter(_.userId === user)

  private def byUsername(username: String) = tables
    .users
    .filter(_.usernameKey === Username.key(username))

  private def sessionsOf(user: Long) = tables.sessions.filter(_.userId === user)

  private def codesOf(user: Long) = tables
    .recoveryCodes
    .filter(_.userId === user)

  private def codeOf(user: Long, codeHash: String) =
    codesOf(user).filter(_.codeHash === codeHash)

  private def allLinksOf(user: Long) = tables
    .emailLinks
    .filter(_.userId === user)

  private def linksOf(user: Long, purpose: EmailPurpose) = allLinksOf(user)
    .filter(_.purpose === purpose.code)

object UserStore:

  /** The number of milliseconds in a day, as long as sent mails are recorded. */
  private val dayMillis: Long = 24 * AuthPolicy.hourMillis

  /** The number of tries to make a guest when others take its number. */
  private val guestTries = 5

  /**
    * The name if free, or else the name with the lowest free number from 2,
    * `taken` holding the keys of the usernames taken.
    */
  private[server] def numbered(name: String, taken: Set[String]): String =
    Iterator
      .from(1)
      .map(number => if number == 1 then name else s"$name $number")
      .find(candidate => !taken(Username.key(candidate)))
      .get

  /**
    * Whether a failure is an integrity constraint violation, such as a username
    * taken meanwhile.
    *
    * @param error
    *   The failure.
    *
    * @return
    *   Whether it is one.
    */
  private[server] def taken(error: Throwable): Boolean =
    violatesConstraint(error)

  /**
    * Whether a failure is an integrity constraint violation, recognised by
    * SQLSTATE class `23`, as drivers differ in their exception types.
    */
  private def violatesConstraint(error: Throwable): Boolean = error match
    case sql: SQLException => Option(sql.getSQLState).exists(_.startsWith("23"))
    case _                 => false
