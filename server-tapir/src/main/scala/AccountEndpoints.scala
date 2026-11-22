package com.alecdorrington.hecate
package server
package tapir

import cats.effect.IO
import com.alecdorrington.hecate.tapir.AccountApi
import sttp.tapir.server.ServerEndpoint

/**
  * A server of the endpoints in [[AccountApi]], backed by an
  * [[AccountService]]. Serve an account's managers beside it as
  * `SharingEndpoints(sharing, Resource.userKind, SharingApi.of("accounts"),
  * auth).grants`.
  *
  * @param accounts
  *   The service that answers the requests.
  *
  * @param auth
  *   The endpoints that resolve sessions to callers and word refusals.
  */
final class AccountEndpoints
  (
    accounts: AccountService,
    auth: AuthEndpoints,
  ):

  /** The server endpoint for [[AccountApi.list]]. */
  lazy val list: ServerEndpoint[Any, IO] =
    auth.served(AccountApi.list)(user => _ => accounts.list(user))

  /** The server endpoint for [[AccountApi.create]]. */
  lazy val create: ServerEndpoint[Any, IO] = auth.served(AccountApi.create)(
    user => creation => accounts.create(user, creation),
  )

  /** The server endpoint for [[AccountApi.managers]]. */
  lazy val managers: ServerEndpoint[Any, IO] =
    auth.served(AccountApi.managers)(user => _ => accounts.managers(user))

  /** The server endpoint for [[AccountApi.rename]]. */
  lazy val rename: ServerEndpoint[Any, IO] = auth.served(AccountApi.rename)(
    user => (account, change) => accounts.rename(user, account, change),
  )

  /** The server endpoint for [[AccountApi.setPassword]]. */
  lazy val setPassword: ServerEndpoint[Any, IO] = auth.served(
    AccountApi.setPassword,
  )(user => (account, setting) => accounts.setPassword(user, account, setting))

  /** The server endpoint for [[AccountApi.suspend]]. */
  lazy val suspend: ServerEndpoint[Any, IO] = auth.served(AccountApi.suspend)(
    user => (account, suspended) => accounts.suspend(user, account, suspended),
  )

  /** The server endpoint for [[AccountApi.release]]. */
  lazy val release: ServerEndpoint[Any, IO] = auth.served(AccountApi.release)(
    user => account => accounts.release(user, account),
  )

  /** The server endpoint for [[AccountApi.delete]]. */
  lazy val delete: ServerEndpoint[Any, IO] = auth.served(AccountApi.delete)(
    user => account => accounts.delete(user, account),
  )

  /** The endpoints to serve. */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(
    list,
    create,
    managers,
    rename,
    setPassword,
    suspend,
    release,
    delete,
  )
