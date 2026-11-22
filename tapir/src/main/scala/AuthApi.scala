package com.alecdorrington.hecate
package tapir

import com.alecdorrington.hecate.api.Protocol.{
  languageCookie, replacementStatus, sessionCookie,
}
import com.alecdorrington.hecate.model.{
  AuthRules, Credentials, EmailChange, EmailConfirmation, EmailStatus,
  PasswordChange, PasswordCheck, PasswordReplacement, PasswordReset,
  PasswordResetRequest, Recovery, RecoveryCodes, User,
}
import com.alecdorrington.hecate.tapir.Schemas.given
import sttp.model.StatusCode
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Descriptions of the endpoints for accounts and sign-in, shared by server and
  * client. Signing in opens a session held in an HTTP-only cookie, which
  * endpoints built on [[secured]] require. Any request may carry a `language`
  * cookie (see [[com.alecdorrington.hecate.api.Protocol.languageCookie]]).
  */
object AuthApi:

  /** The session and language cookies, each optional, of a secured endpoint. */
  type Security = (Option[String], Option[String])

  /**
    * An endpoint that requires a signed-in user, refused with a sentence.
    *
    * @tparam I
    *   The type of the request's input.
    *
    * @tparam O
    *   The type of the reply's output.
    */
  type Secured[I, O] = Endpoint[Security, I, String, O, Any]

  /**
    * An endpoint that anybody may call, refused with a sentence.
    *
    * @tparam I
    *   The type of the request's input.
    *
    * @tparam O
    *   The type of the reply's output.
    */
  type Open[I, O] = PublicEndpoint[I, String, O, Any]

  private[tapir] val language: EndpointInput.Cookie[Option[String]] =
    cookie[Option[String]](languageCookie)

  private val session: EndpointInput.Cookie[Option[String]] =
    cookie[Option[String]](sessionCookie)

  private[tapir] val base: Open[Unit, Unit] = endpoint.errorOut(stringBody)

  /**
    * The base of every endpoint that requires a signed-in user, in this library
    * and the host alike. Its security logic resolves the session cookie to a
    * [[com.alecdorrington.hecate.model.Caller]], or refuses in the language the
    * language cookie names.
    */
  val secured: Secured[Unit, Unit] = base
    .securityIn(session)
    .securityIn(language)

  /** An endpoint that registers an account and signs it in. */
  val register
    : Open[
      (Credentials, Option[String]),
      (User, CookieValueWithMeta),
    ] = base
    .post
    .in("api" / "auth" / "register")
    .in(jsonBody[Credentials])
    .in(language)
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))

  /**
    * An endpoint that claims the signed-in guest's account with a username and
    * password. Signs out every other session and renews this one's cookie.
    */
  val claim
    : Secured[
      Credentials,
      (User, CookieValueWithMeta),
    ] = secured
    .post
    .in("api" / "auth" / "claim")
    .in(jsonBody[Credentials])
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))

  /**
    * An endpoint that signs in to an existing account. A sign-in refused
    * because someone else set the account's password, which its user must
    * replace first ([[replacePassword]]), answers
    * [[com.alecdorrington.hecate.api.Protocol.replacementStatus]] rather than
    * `400`, beside its sentence.
    */
  val signIn
    : PublicEndpoint[
      (Credentials, Option[String]),
      (StatusCode, String),
      (User, CookieValueWithMeta),
      Any,
    ] = endpoint
    .errorOut(statusCode)
    .errorOut(stringBody)
    .post
    .in("api" / "auth" / "sign-in")
    .in(jsonBody[Credentials])
    .in(language)
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))

  /** An endpoint that closes the current session and clears its cookie. */
  val signOut: Open[Option[String], CookieValueWithMeta] = base
    .post
    .in("api" / "auth" / "sign-out")
    .in(session)
    .out(setCookie(sessionCookie))

  /** An endpoint that identifies the signed-in user, if any. */
  val current: Open[Option[String], Option[User]] = base
    .get
    .in("api" / "auth" / "me")
    .in(session)
    .out(jsonBody[Option[User]])

  /** An endpoint that describes the server's rules for accounts. */
  val rules: Open[Unit, AuthRules] = base
    .get
    .in("api" / "auth" / "rules")
    .out(jsonBody[AuthRules])

  /**
    * An endpoint that changes the signed-in user's password, given their
    * current one. Signs out every other session and renews this one's cookie.
    */
  val changePassword: Secured[PasswordChange, CookieValueWithMeta] = secured
    .put
    .in("api" / "auth" / "password")
    .in(jsonBody[PasswordChange])
    .out(setCookie(sessionCookie))

  /**
    * An endpoint that issues a new set of recovery codes, given the user's
    * password, invalidating every earlier code. The codes are returned only
    * this once.
    */
  val generateRecoveryCodes: Secured[PasswordCheck, RecoveryCodes] = secured
    .post
    .in("api" / "auth" / "recovery-codes")
    .in(jsonBody[PasswordCheck])
    .out(jsonBody[RecoveryCodes])

  /** An endpoint that counts the signed-in user's unused recovery codes. */
  val recoveryCodesLeft: Secured[Unit, Int] = secured
    .get
    .in("api" / "auth" / "recovery-codes")
    .out(jsonBody[Int])

  /**
    * An endpoint that signs in to an account whose password someone else set,
    * replacing it with the user's own: as sign-in, but with the replacement,
    * which must differ.
    */
  val replacePassword
    : Open[
      (PasswordReplacement, Option[String]),
      (User, CookieValueWithMeta),
    ] = base
    .post
    .in("api" / "auth" / "password" / "replace")
    .in(jsonBody[PasswordReplacement])
    .in(language)
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))

  /** The status of a refused sign-in, of the reason given. */
  def signInStatus(replacing: Boolean): StatusCode =
    if replacing then StatusCode(replacementStatus) else StatusCode.BadRequest

  /**
    * An endpoint that regains an account with a recovery code: sets a new
    * password, signs out every other session, and signs in.
    */
  val recover
    : Open[
      (Recovery, Option[String]),
      (User, CookieValueWithMeta),
    ] = base
    .post
    .in("api" / "auth" / "recover")
    .in(jsonBody[Recovery])
    .in(language)
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))

  /**
    * An endpoint that deletes the signed-in user's account, given their
    * password, and clears the session cookie.
    */
  val deleteAccount: Secured[PasswordCheck, CookieValueWithMeta] = secured
    .post
    .in("api" / "auth" / "account" / "delete")
    .in(jsonBody[PasswordCheck])
    .out(setCookie(sessionCookie))

  /**
    * An endpoint that emails a link to reset a forgotten password to every
    * account with the given confirmed address. It answers alike whether or not
    * any account has the address, so as to disclose nothing.
    */
  val requestPasswordReset
    : Open[
      (PasswordResetRequest, Option[String]),
      Unit,
    ] = base
    .post
    .in("api" / "auth" / "password" / "request-reset")
    .in(jsonBody[PasswordResetRequest])
    .in(language)

  /**
    * An endpoint that resets a forgotten password with a link sent by email:
    * sets a new password, signs out every other session, and signs in.
    */
  val resetPassword
    : Open[
      (PasswordReset, Option[String]),
      (User, CookieValueWithMeta),
    ] = base
    .post
    .in("api" / "auth" / "password" / "reset")
    .in(jsonBody[PasswordReset])
    .in(language)
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))

  /**
    * An endpoint that answers the signed-in user's email address, and any
    * address awaiting confirmation.
    */
  val email: Secured[Unit, EmailStatus] = secured
    .get
    .in("api" / "auth" / "email")
    .out(jsonBody[EmailStatus])

  /**
    * An endpoint that changes the signed-in user's email address, given their
    * password. A new address is sent a link to confirm it; `None` removes the
    * address.
    */
  val changeEmail: Secured[EmailChange, EmailStatus] = secured
    .put
    .in("api" / "auth" / "email")
    .in(jsonBody[EmailChange])
    .out(jsonBody[EmailStatus])

  /** An endpoint that sends a new link to the address awaiting confirmation. */
  val resendConfirmation: Secured[Unit, EmailStatus] = secured
    .post
    .in("api" / "auth" / "email" / "resend")
    .out(jsonBody[EmailStatus])

  /**
    * An endpoint that confirms an email address with the link sent to it. It
    * needs no signed-in user, as the link's secret is proof enough.
    */
  val confirmEmail
    : Open[
      (EmailConfirmation, Option[String]),
      Unit,
    ] = base
    .post
    .in("api" / "auth" / "email" / "confirm")
    .in(jsonBody[EmailConfirmation])
    .in(language)
