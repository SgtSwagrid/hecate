package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  AuthRules, Credentials, EmailChange, EmailConfirmation, EmailStatus, Guest,
  LinkTarget, PasswordChange, PasswordCheck, PasswordReset,
  PasswordResetRequest, Recovery, RecoveryCodes, User, Welcome,
}
import com.raquo.laminar.api.L.*
import io.circe.{Decoder, Encoder}
import io.circe.parser.decode
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.URIUtils.encodeURIComponent

/**
  * A browser-side store of who is signed in and of requests about their
  * account, driving the endpoints under `/api/auth`. The session itself is an
  * HTTP-only cookie. Headless: it exposes signals and commands, and the host
  * renders.
  *
  * @param wording
  *   The wording of this state's own messages. The server's refusals arrive
  *   already worded, in the language of the `language` cookie.
  */
final class AuthState(wording: Wording = Wording.english):

  // Bound to the page, not a view, so that every request arrives whether or not
  // anything is on screen.
  private given Owner = unsafeWindowOwner

  private val userVar: Var[Option[User]] = Var(None)

  private val readyVar: Var[Boolean] = Var(false)

  private val errorVar: Var[Option[String]] = Var(None)

  private val noticeVar: Var[Option[String]] = Var(None)

  private val pendingVar: Var[Boolean] = Var(false)

  private val recoveryCodesVar: Var[Option[List[String]]] = Var(None)

  private val recoveryCodesLeftVar: Var[Option[Int]] = Var(None)

  private val emailVar: Var[Option[EmailStatus]] = Var(None)

  /** Whether a [[recheck]] is in flight, so that a burst of refusals asks once. */
  private var rechecking = false

  /**
    * The signed-in user, or `None` while nobody is signed in. Its `changes`
    * fire once the startup probe answers and on every sign-in and sign-out.
    */
  val user: Signal[Option[User]] = userVar.signal

  /** The id of the signed-in user, changing only as someone signs in or out. */
  val userId: Signal[Option[Long]] = user.map(_.map(_.id)).distinct

  /**
    * Whether the startup probe has answered. Until it has, a `None` [[user]]
    * does not mean signed out.
    */
  val ready: Signal[Boolean] = readyVar.signal

  /** The reason the last request about the account was refused, if any. */
  val error: Signal[Option[String]] = errorVar.signal

  /**
    * The confirmation that the last request about the account succeeded, where
    * its success is not otherwise visible.
    */
  val notice: Signal[Option[String]] = noticeVar.signal

  /** Whether a request about the account is in flight. */
  val pending: Signal[Boolean] = pendingVar.signal

  /**
    * The server's rules for accounts, fetched once, or `None` until they
    * arrive.
    */
  val rules: Signal[Option[AuthRules]] =
    fetched("/api/auth/rules")(decoded[AuthRules]).startWith(None).observe

  /**
    * The recovery codes just generated, which the server never shows again,
    * until [[dismissRecoveryCodes]].
    */
  val recoveryCodes: Signal[Option[List[String]]] = recoveryCodesVar.signal

  /** The number of unused recovery codes the signed-in user has, once known. */
  val recoveryCodesLeft: Signal[Option[Int]] = recoveryCodesLeftVar.signal

  /**
    * The signed-in user's email addresses, once known. Stays `None` where the
    * server sends no email.
    */
  val email: Signal[Option[EmailStatus]] = emailVar.signal

  locally:
    probe(0)
    user
      .updates
      .foreach: found =>
        recoveryCodesVar.set(None)
        if found.isDefined then refreshAccount()
        else
          recoveryCodesLeftVar.set(None)
          emailVar.set(None)

  /**
    * Asks the server who is signed in, retrying when the request never arrives,
    * as adopting that silence would sign a signed-in user out. After
    * [[AuthState.retries]] retries, nobody is taken to be signed in.
    */
  private def probe(retry: Int): Unit = Outcome.once(whoIsSignedIn):
    case Some(found)                       => settle(found)
    case None if retry < AuthState.retries => reprobe(retry)
    case None                              => settle(None)

  private def settle(found: Option[User]): Unit =
    userVar.set(found)
    readyVar.set(true)

  private def reprobe(retry: Int): Unit = Outcome.once(
    EventStream.fromValue(()).delay(AuthState.retryMillis),
  )(_ => probe(retry + 1))

  /**
    * Registers an account and signs in to it.
    *
    * @param username
    *   The username of the new account.
    *
    * @param password
    *   The password of the new account.
    */
  def register(username: String, password: String): Unit = signInWith(
    "/api/auth/register",
    Credentials(username, password),
  )

  /**
    * Signs in to an existing account.
    *
    * @param username
    *   The username of the account.
    *
    * @param password
    *   The password of the account.
    */
  def signIn(username: String, password: String): Unit = signInWith(
    "/api/auth/sign-in",
    Credentials(username, password),
  )

  /**
    * Follows an invite link as a new guest and signs them in. If the link leads
    * nowhere, no guest is made and [[error]] says why.
    *
    * @param code
    *   The code of the link.
    *
    * @param name
    *   The name the guest gives, which becomes their username.
    *
    * @param arrived
    *   The callback given the link's target just before the guest is signed in.
    */
  def welcome(code: String, name: String)(arrived: LinkTarget => Unit): Unit =
    perform(Fetch.post(
      s"/api/invite-links/${ encodeURIComponent(code) }/welcome",
      body = Guest(name),
    )): response =>
      adopt[Welcome](response): welcome =>
        arrived(welcome.target)
        userVar.set(Some(welcome.user))

  /**
    * Claims the signed-in guest's account with a username and password, signing
    * out every other session.
    *
    * @param username
    *   The username to sign in with.
    *
    * @param password
    *   The password to sign in with.
    */
  def claim(username: String, password: String): Unit = signInWith(
    "/api/auth/claim",
    Credentials(username, password),
    Some(wording.accountClaimed),
  )

  /**
    * Regains an account with a recovery code, setting a new password and
    * signing in.
    *
    * @param username
    *   The username of the account.
    *
    * @param code
    *   The unused recovery code.
    *
    * @param replacement
    *   The new password.
    */
  def recover
    (
      username: String,
      code: String,
      replacement: String,
    )
    : Unit = signInWith(
    "/api/auth/recover",
    Recovery(username, code, replacement),
  )

  /**
    * Asks for a link to reset a forgotten password to be emailed to every
    * account with an address. The server and [[notice]] answer alike whether or
    * not any account has it.
    *
    * @param address
    *   The email address.
    */
  def requestPasswordReset(address: String): Unit = perform(Fetch.post(
    "/api/auth/password/request-reset",
    body = PasswordResetRequest(address),
  ))(_ => noticeVar.set(Some(wording.resetLinkSent)))

  /**
    * Resets a forgotten password with a link sent by email and signs in,
    * signing out every other session.
    *
    * @param token
    *   The secret the link carries.
    *
    * @param replacement
    *   The new password.
    */
  def resetPassword(token: String, replacement: String): Unit = signInWith(
    "/api/auth/password/reset",
    PasswordReset(token, replacement),
    Some(wording.passwordChanged),
  )

  /**
    * Changes the signed-in user's email address. A new address becomes theirs
    * once they open the link sent to it.
    *
    * @param address
    *   The new address, or `None` to remove it.
    *
    * @param password
    *   The user's password.
    */
  def changeEmail
    (
      address: Option[String],
      password: String,
    )
    : Unit = perform(Fetch.put(
    "/api/auth/email",
    body = EmailChange(address, password),
  )): response =>
    adoptEmail(response)(status =>
      status
        .pending
        .map(wording.confirmationSent)
        .orElse(Option.when(address.isEmpty)(wording.emailRemoved)),
    )

  /** Sends a new link to the address awaiting confirmation. */
  def resendConfirmation(): Unit = perform(
    Fetch.post("/api/auth/email/resend"),
  )(adoptEmail(_)(_.pending.map(wording.confirmationSent)))

  /**
    * Confirms an email address with the link sent to it, whoever is signed in.
    *
    * @param token
    *   The secret the link carries.
    */
  def confirmEmail(token: String): Unit = perform(Fetch.post(
    "/api/auth/email/confirm",
    body = EmailConfirmation(token),
  )): _ =>
    noticeVar.set(Some(wording.emailConfirmed))
    if userVar.now().isDefined then refreshEmail()

  /** Signs out, closing the session on the server. */
  def signOut(): Unit =
    clearMessages()
    Outcome.once(Outcome.of(Fetch.post("/api/auth/sign-out")))(_ =>
      userVar.set(None),
    )

  /**
    * Changes the signed-in user's password, signing out every other session.
    *
    * @param current
    *   The user's current password.
    *
    * @param replacement
    *   The new password.
    */
  def changePassword(current: String, replacement: String): Unit =
    perform(Fetch.put(
      "/api/auth/password",
      body = PasswordChange(current, replacement),
    ))(_ => noticeVar.set(Some(wording.passwordChanged)))

  /**
    * Generates a new set of recovery codes, shown in [[recoveryCodes]],
    * invalidating every earlier code.
    *
    * @param password
    *   The signed-in user's password.
    */
  def generateRecoveryCodes(password: String): Unit = perform(Fetch.post(
    "/api/auth/recovery-codes",
    body = PasswordCheck(password),
  )): response =>
    adopt[RecoveryCodes](response): codes =>
      recoveryCodesVar.set(Some(codes.codes))
      recoveryCodesLeftVar.set(Some(codes.codes.size))

  /** Forgets the recovery codes just shown. */
  def dismissRecoveryCodes(): Unit = recoveryCodesVar.set(None)

  /**
    * Deletes the signed-in user's account, then signs them out.
    *
    * @param password
    *   The user's password, not read for a guest.
    */
  def deleteAccount(password: String): Unit = perform(Fetch.post(
    "/api/auth/account/delete",
    body = PasswordCheck(password),
  ))(_ => userVar.set(None))

  /**
    * Asks the server again who is signed in, for a host whose secured request
    * was refused: an expired session signs the user out, and a plain refusal
    * changes nothing. A request that never arrives changes nothing, and calls
    * made while one is in flight are dropped.
    */
  def recheck(): Unit = if !rechecking then
    rechecking = true
    Outcome.once(whoIsSignedIn): answer =>
      rechecking = false
      answer.foreach(userVar.set)

  /**
    * Rereads who is signed in, their recovery codes left and their email
    * address, as after a change to the account elsewhere. The same user,
    * unchanged, is kept, so that codes just generated stay shown; anyone else
    * is adopted, as by [[recheck]].
    */
  def refresh(): Unit = Outcome.once(whoIsSignedIn):
    case Some(found) if found == userVar.now() =>
      if found.isDefined then refreshAccount()
    case Some(found) => userVar.set(found)
    case None        => ()

  /** Discards the last error and notice. */
  def clearMessages(): Unit =
    errorVar.set(None)
    noticeVar.set(None)

  /**
    * Runs a request to a secured endpoint, rechecking the session on a refusal,
    * since an ended session is refused like anything else.
    *
    * @param request
    *   The stream of the request's one reply.
    *
    * @return
    *   A stream of its one outcome.
    */
  def outcome(request: EventStream[Response]): EventStream[Outcome] = Outcome
    .of(request)
    .map: outcome =>
      outcome match
        case Outcome.Refused(_) => recheck()
        case _                  => ()
      outcome

  /**
    * Runs a request as [[outcome]] does, reading its answer, or why there is
    * none. An empty reply reads as `null`, as one answering `Unit` does.
    *
    * @tparam X
    *   The type of the answer.
    *
    * @param request
    *   The stream of the request's one reply.
    *
    * @return
    *   A stream of the answer, or the reason there is none.
    */
  def explained[X : Decoder]
    (request: EventStream[Response])
    : EventStream[Either[String, X]] = outcome(request).map:
    case Outcome.Answered(response) =>
      decode[X](if response.data.isBlank then "null" else response.data)
        .left
        .map(_ => wording.unreadableReply)
    case Outcome.Refused(reason) => Left(reason)
    case Outcome.Unreachable(_)  => Left(wording.unreachable)

  private def refreshAccount(): Unit =
    refreshRecoveryCodesLeft()
    refreshEmail()

  private def refreshEmail(): Unit =
    Outcome.once(fetched("/api/auth/email")(decoded[EmailStatus]))(emailVar.set)

  private def adoptEmail
    (response: Response)
    (notice: EmailStatus => Option[String])
    : Unit = adopt[EmailStatus](response): status =>
    emailVar.set(Some(status))
    noticeVar.set(notice(status))

  private def refreshRecoveryCodesLeft(): Unit = Outcome.once(
    fetched("/api/auth/recovery-codes")(decoded[Int]),
  )(recoveryCodesLeftVar.set)

  private def signInWith[X : Encoder]
    (
      url: String,
      body: X,
      notice: Option[String] = None,
    )
    : Unit = perform(Fetch.post(url, body = body)): response =>
    adopt[User](response): user =>
      userVar.set(Some(user))
      if notice.isDefined then noticeVar.set(notice)

  private def perform
    (request: EventStream[Response])
    (succeeded: Response => Unit)
    : Unit =
    clearMessages()
    Outcome.tracked(Outcome.of(request), pendingVar):
      case Outcome.Answered(response) => succeeded(response)
      case Outcome.Refused(reason)    => errorVar.set(Some(reason))
      case Outcome.Unreachable(_)     => errorVar.set(Some(wording.unreachable))

  /** Asks who is signed in: `None` when there is no answer. */
  private def whoIsSignedIn: EventStream[Option[Option[User]]] =
    fetched("/api/auth/me")(signedIn)

  /** `None` when the request never reaches the server. */
  private def fetched[X]
    (url: String)
    (read: Response => Option[X])
    : EventStream[Option[X]] = Fetch
    .get(url)
    .map(read)
    .recover { case _ => Some(None) }

  private def adopt[X : Decoder](response: Response)(use: X => Unit): Unit =
    decoded[X](response).fold(errorVar.set(Some(wording.unreadableReply)))(use)

  // Nobody signed in is sent as an empty body, not `null`, which would
  // otherwise read as no answer and leave an ended session unnoticed.
  private def signedIn(response: Response): Option[Option[User]] =
    if response.status < 400 && response.data.isBlank then Some(None)
    else decoded[Option[User]](response)

  private def decoded[X : Decoder](response: Response): Option[X] = Option
    .when(response.status < 400)(decode[X](response.data).toOption)
    .flatten

object AuthState:

  /** The number of retries of an unanswered startup probe. */
  private val retries = 3

  private val retryMillis = 1000
