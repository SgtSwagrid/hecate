package com.alecdorrington.hecate
package server
package tapir

import cats.effect.IO
import com.alecdorrington.hecate.tapir.GroupApi
import sttp.tapir.server.ServerEndpoint

/**
  * A server of the endpoints in [[GroupApi]], backed by a [[GroupService]].
  *
  * @param groups
  *   The service that answers the requests.
  *
  * @param auth
  *   The endpoints that resolve sessions to callers and word refusals.
  */
final class GroupEndpoints
  (
    groups: GroupService,
    auth: AuthEndpoints,
  ):

  /** The server endpoint for [[GroupApi.list]]. */
  lazy val list: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.list)(user => _ => groups.list(user))

  /** The server endpoint for [[GroupApi.principals]]. */
  lazy val principals: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.principals)(user => _ => groups.principals(user))

  /** The server endpoint for [[GroupApi.mine]]. */
  lazy val mine: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.mine)(user => _ => groups.mine(user))

  /** The server endpoint for [[GroupApi.joinable]]. */
  lazy val joinable: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.joinable)(user => _ => groups.joinable(user))

  /** The server endpoint for [[GroupApi.leave]]. */
  lazy val leave: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.leave)(user => group => groups.leave(user, group))

  /** The server endpoint for [[GroupApi.join]]. */
  lazy val join: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.join)(user => group => groups.join(user, group))

  /** The server endpoint for [[GroupApi.request]]. */
  lazy val request: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.request)(user => group => groups.request(user, group))

  /** The server endpoint for [[GroupApi.withdraw]]. */
  lazy val withdraw: ServerEndpoint[Any, IO] = auth.served(GroupApi.withdraw)(
    user => group => groups.withdraw(user, group),
  )

  /** The server endpoint for [[GroupApi.create]]. */
  lazy val create: ServerEndpoint[Any, IO] = auth.served(GroupApi.create)(
    user => details => groups.create(user, details),
  )

  /** The server endpoint for [[GroupApi.update]]. */
  lazy val update: ServerEndpoint[Any, IO] = auth.served(GroupApi.update)(
    user => (group, details) => groups.update(user, group, details),
  )

  /** The server endpoint for [[GroupApi.delete]]. */
  lazy val delete: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.delete)(user => group => groups.delete(user, group))

  /** The server endpoint for [[GroupApi.setPublic]]. */
  lazy val setPublic: ServerEndpoint[Any, IO] = auth.served(GroupApi.setPublic)(
    user => (group, public) => groups.setPublic(user, group, public),
  )

  /** The server endpoint for [[GroupApi.link]]. */
  lazy val link: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.link)(user => group => groups.link(user, group))

  /** The server endpoint for [[GroupApi.relink]]. */
  lazy val relink: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.relink)(user => group => groups.relink(user, group))

  /** The server endpoint for [[GroupApi.unlink]]. */
  lazy val unlink: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.unlink)(user => group => groups.unlink(user, group))

  /** The server endpoint for [[GroupApi.invite]]. */
  lazy val invite: ServerEndpoint[Any, IO] = auth.served(GroupApi.invite)(
    user => (group, invitee) => groups.invite(user, group, invitee),
  )

  /** The server endpoint for [[GroupApi.admit]]. */
  lazy val admit: ServerEndpoint[Any, IO] = auth.served(GroupApi.admit)(user =>
    (group, person) => groups.admit(user, group, person),
  )

  /** The server endpoint for [[GroupApi.remove]]. */
  lazy val remove: ServerEndpoint[Any, IO] = auth.served(GroupApi.remove)(
    user => (group, person) => groups.remove(user, group, person),
  )

  /** The server endpoint for [[GroupApi.invitations]]. */
  lazy val invitations: ServerEndpoint[Any, IO] =
    auth.served(GroupApi.invitations)(user => _ => groups.invitations(user))

  /** The server endpoint for [[GroupApi.accept]]. */
  lazy val accept: ServerEndpoint[Any, IO] = auth.served(GroupApi.accept)(
    user => invitation => groups.accept(user, invitation),
  )

  /** The server endpoint for [[GroupApi.decline]]. */
  lazy val decline: ServerEndpoint[Any, IO] = auth.served(GroupApi.decline)(
    user => invitation => groups.decline(user, invitation),
  )

  /** The server endpoint for [[GroupApi.removeGroup]]. */
  lazy val removeGroup: ServerEndpoint[Any, IO] = auth.served(
    GroupApi.removeGroup,
  )(user => (group, member) => groups.removeGroup(user, group, member))

  /** The server endpoint for [[GroupApi.groupInvitations]]. */
  lazy val groupInvitations: ServerEndpoint[Any, IO] = auth.served(
    GroupApi.groupInvitations,
  )(user => _ => groups.groupInvitations(user))

  /** The server endpoint for [[GroupApi.acceptGroup]]. */
  lazy val acceptGroup: ServerEndpoint[Any, IO] = auth.served(
    GroupApi.acceptGroup,
  )(user => invitation => groups.acceptGroup(user, invitation))

  /** The server endpoint for [[GroupApi.declineGroup]]. */
  lazy val declineGroup: ServerEndpoint[Any, IO] = auth.served(
    GroupApi.declineGroup,
  )(user => invitation => groups.declineGroup(user, invitation))

  /** The endpoints to serve. */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(
    list,
    principals,
    mine,
    joinable,
    leave,
    join,
    request,
    withdraw,
    create,
    update,
    delete,
    setPublic,
    link,
    relink,
    unlink,
    invite,
    admit,
    remove,
    invitations,
    accept,
    decline,
    removeGroup,
    groupInvitations,
    acceptGroup,
    declineGroup,
  )
