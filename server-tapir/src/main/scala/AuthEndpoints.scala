package com.alecdorrington.hecate
package server
package tapir

import cats.effect.IO
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{AuthRefusal, Caller, User}
import com.alecdorrington.hecate.server.tapir.AuthEndpoints.cookie
import com.alecdorrington.hecate.tapir.AuthApi
import sttp.model.headers.Cookie.SameSite
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.server.ServerEndpoint

/**
  * A server of the endpoints in [[AuthApi]], backed by an [[AuthService]], with
  * the security logic of every endpoint built on [[AuthApi.secured]], the
  * host's included.
  *
  * @param auth
  *   The service that answers the requests.
  *
  * @param wording
  *   The wording of refusals for the language a request names, or for `None`
  *   when it names none.
  */
final class AuthEndpoints
  (
    auth: AuthService,
    wording: Option[String] => Wording = _ => Wording.english,
  ):

  /** The server endpoint for [[AuthApi.register]]. */
  lazy val register: ServerEndpoint[Any, IO] =
    signingIn(AuthApi.register)(auth.register)

  /** The server endpoint for [[AuthApi.claim]]. */
  lazy val claim: ServerEndpoint[Any, IO] = served(AuthApi.claim)(user =>
    credentials => signedIn(auth.claim(user, credentials)),
  )

  /**
    * The server endpoint for [[AuthApi.signIn]], whose refusal to an account
    * whose password must first be replaced has a status of its own.
    */
  lazy val signIn: ServerEndpoint[Any, IO] = AuthApi
    .signIn
    .serverLogic((credentials, locale) =>
      auth
        .signIn(credentials)
        .map(
          _.left
            .map(refusal =>
              (
                AuthApi.signInStatus(
                  refusal == AuthRefusal.PasswordChangeRequired,
                ),
                wording(locale).phrase(refusal),
              ),
            )
            .map((user, session) => (user, cookie(session))),
        ),
    )

  /** The server endpoint for [[AuthApi.replacePassword]]. */
  lazy val replacePassword: ServerEndpoint[Any, IO] =
    signingIn(AuthApi.replacePassword)(auth.replacePassword)

  /** The server endpoint for [[AuthApi.signOut]]. */
  lazy val signOut: ServerEndpoint[Any, IO] = AuthApi
    .signOut
    .serverLogic(token => worded(None)(auth.signOut(token)).map(_.map(cookie)))

  /** The server endpoint for [[AuthApi.current]]. */
  lazy val current: ServerEndpoint[Any, IO] = AuthApi
    .current
    .serverLogic(token => worded(None)(auth.current(token)))

  /** The server endpoint for [[AuthApi.rules]]. */
  lazy val rules: ServerEndpoint[Any, IO] = AuthApi
    .rules
    .serverLogicSuccess(_ => IO.pure(auth.rules))

  /** The server endpoint for [[AuthApi.changePassword]]. */
  lazy val changePassword: ServerEndpoint[Any, IO] = served(
    AuthApi.changePassword,
  )(user => change => auth.changePassword(user, change).map(_.map(cookie)))

  /** The server endpoint for [[AuthApi.generateRecoveryCodes]]. */
  lazy val generateRecoveryCodes: ServerEndpoint[Any, IO] = served(
    AuthApi.generateRecoveryCodes,
  )(user => check => auth.generateRecoveryCodes(user, check.password))

  /** The server endpoint for [[AuthApi.recoveryCodesLeft]]. */
  lazy val recoveryCodesLeft: ServerEndpoint[Any, IO] =
    served(AuthApi.recoveryCodesLeft)(user => _ => auth.recoveryCodesLeft(user))

  /** The server endpoint for [[AuthApi.recover]]. */
  lazy val recover: ServerEndpoint[Any, IO] =
    signingIn(AuthApi.recover)(auth.recover)

  /** The server endpoint for [[AuthApi.deleteAccount]]. */
  lazy val deleteAccount: ServerEndpoint[Any, IO] =
    served(AuthApi.deleteAccount)(user =>
      check => auth.deleteAccount(user, check.password).map(_.map(cookie)),
    )

  /** The server endpoint for [[AuthApi.requestPasswordReset]]. */
  lazy val requestPasswordReset: ServerEndpoint[Any, IO] =
    servedOpen(AuthApi.requestPasswordReset)(auth.requestPasswordReset)

  /** The server endpoint for [[AuthApi.resetPassword]]. */
  lazy val resetPassword: ServerEndpoint[Any, IO] =
    signingIn(AuthApi.resetPassword)(auth.resetPassword)

  /** The server endpoint for [[AuthApi.email]]. */
  lazy val email: ServerEndpoint[Any, IO] =
    served(AuthApi.email)(user => _ => auth.email(user))

  /** The server endpoint for [[AuthApi.changeEmail]]. */
  lazy val changeEmail: ServerEndpoint[Any, IO] = servedTo(AuthApi.changeEmail)(
    caller => change => auth.changeEmail(caller.user, change, caller.locale),
  )

  /** The server endpoint for [[AuthApi.resendConfirmation]]. */
  lazy val resendConfirmation: ServerEndpoint[Any, IO] = servedTo(
    AuthApi.resendConfirmation,
  )(caller => _ => auth.resendConfirmation(caller.user, caller.locale))

  /** The server endpoint for [[AuthApi.confirmEmail]]. */
  lazy val confirmEmail: ServerEndpoint[Any, IO] =
    servedOpen(AuthApi.confirmEmail)(auth.confirmEmail)

  /**
    * The endpoints to serve. Account deletion is left out where the service
    * offers none, and the email endpoints where it sends no email.
    */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(
    register,
    claim,
    signIn,
    replacePassword,
    signOut,
    current,
    rules,
    changePassword,
    generateRecoveryCodes,
    recoveryCodesLeft,
    recover,
  ) ++ Option.when(auth.rules.accountDeletion)(deleteAccount) ++
    Option
      .when(auth.rules.email)(List(
        requestPasswordReset,
        resetPassword,
        email,
        changeEmail,
        resendConfirmation,
        confirmEmail,
      ))
      .toList
      .flatten

  /**
    * Resolves a request's session cookie to its caller: the security logic of
    * every endpoint built on [[AuthApi.secured]].
    *
    * @param security
    *   The request's session and language cookies.
    *
    * @return
    *   An effect producing the caller, or a refusal worded in their language.
    */
  def authenticate(security: AuthApi.Security): IO[Either[String, Caller]] =
    val (token, locale) = security
    worded(locale)(auth.signedIn(token)).map(_.map(Caller(_, locale)))

  /** Serves an endpoint built on [[AuthApi.secured]], wording any refusal. */
  private[tapir] def served[I, O]
    (endpoint: AuthApi.Secured[I, O])
    (run: User => I => Answer[O])
    : ServerEndpoint[Any, IO] = servedTo(endpoint)(caller => run(caller.user))

  private def servedTo[I, O]
    (endpoint: AuthApi.Secured[I, O])
    (run: Caller => I => Answer[O])
    : ServerEndpoint[Any, IO] = endpoint
    .serverSecurityLogic(authenticate)
    .serverLogic(caller => input => worded(caller.locale)(run(caller)(input)))

  private def servedOpen[I, O]
    (endpoint: AuthApi.Open[(I, Option[String]), O])
    (run: (I, Option[String]) => Answer[O])
    : ServerEndpoint[Any, IO] =
    endpoint.serverLogic((input, locale) => worded(locale)(run(input, locale)))

  private def signingIn[I]
    (
      endpoint: AuthApi.Open[
        (I, Option[String]),
        (User, CookieValueWithMeta),
      ],
    )
    (run: I => Answer[(User, SessionCookie)])
    : ServerEndpoint[Any, IO] =
    servedOpen(endpoint)((input, _) => signedIn(run(input)))

  private def signedIn
    (answer: Answer[(User, SessionCookie)])
    : Answer[(User, CookieValueWithMeta)] =
    answer.map(_.map((user, session) => (user, cookie(session))))

  private[tapir] def worded[X]
    (locale: Option[String])
    (answer: Answer[X])
    : IO[Either[String, X]] = answer.map(_.left.map(wording(locale).phrase))

object AuthEndpoints:

  private[tapir] def cookie(session: SessionCookie): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = session.token,
      maxAge = Some(session.maxAge),
      path = Some("/"),
      httpOnly = true,
      sameSite = Some(SameSite.Strict),
    )
