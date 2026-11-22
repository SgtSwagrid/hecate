package com.alecdorrington.hecate
package tapir

import com.alecdorrington.hecate.model.{
  AccountCreation, CreatedAccount, ManagedAccount, Manager, PasswordSetting,
  User, UsernameChange,
}
import com.alecdorrington.hecate.tapir.Schemas.given
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Descriptions of the endpoints for accounts that others manage: an account is
  * a resource whose managers are shared through [[SharingApi]] (served as
  * `SharingApi.of("accounts")`, its grants alone) as any resource's holders
  * are. View sees an account, Edit renames it, sets its password, suspends it
  * and answers its invitations (through [[GroupApi.accept]] and
  * [[GroupApi.decline]]), and Own deletes it, releases it and chooses who
  * manages it. Nobody manages their own account.
  */
object AccountApi:

  private def account: EndpointInput[Long] = "api" / "accounts" /
    path[Long]("account")

  /**
    * An endpoint that lists the accounts the signed-in user manages, holding at
    * least view access over them.
    */
  val list: AuthApi.Secured[Unit, List[ManagedAccount]] = AuthApi
    .secured
    .get
    .in("api" / "accounts")
    .out(jsonBody[List[ManagedAccount]])

  /**
    * An endpoint that creates accounts managed by the signed-in user, or by a
    * principal they may address, each joining a group they may change at once,
    * and answers each with its password, this once.
    */
  val create: AuthApi.Secured[AccountCreation, List[CreatedAccount]] = AuthApi
    .secured
    .post
    .in("api" / "accounts")
    .in(jsonBody[AccountCreation])
    .out(jsonBody[List[CreatedAccount]])

  /**
    * An endpoint that lists whoever manages the signed-in user's own account,
    * none for an account its user manages alone.
    */
  val managers: AuthApi.Secured[Unit, List[Manager]] = AuthApi
    .secured
    .get
    .in("api" / "auth" / "managers")
    .out(jsonBody[List[Manager]])

  /** An endpoint that renames an account, returning its user as renamed. */
  val rename: AuthApi.Secured[(Long, UsernameChange), User] = AuthApi
    .secured
    .put
    .in(account / "username")
    .in(jsonBody[UsernameChange])
    .out(jsonBody[User])

  /**
    * An endpoint that sets an account's password, signing out every session of
    * it.
    */
  val setPassword: AuthApi.Secured[(Long, PasswordSetting), Unit] = AuthApi
    .secured
    .put
    .in(account / "password")
    .in(jsonBody[PasswordSetting])

  /**
    * An endpoint that suspends an account, signing out every session of it and
    * letting nobody sign in, or lifts its suspension.
    */
  val suspend: AuthApi.Secured[(Long, Boolean), Unit] = AuthApi
    .secured
    .put
    .in(account / "suspended")
    .in(jsonBody[Boolean])

  /**
    * An endpoint that releases an account: withdraws every grant over it, so
    * that its user alone manages it again.
    */
  val release: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .post
    .in(account / "release")

  /** An endpoint that deletes an account and everything that belongs to it. */
  val delete: AuthApi.Secured[Long, Unit] = AuthApi.secured.delete.in(account)
