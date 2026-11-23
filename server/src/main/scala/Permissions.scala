package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{Access, Resource}
import slick.dbio.DBIO

/**
  * A resolver of the stored access users hold, joining the grants over a
  * resource with the principals each user acts as: themselves, the groups they
  * belong to, and the system if the host names them as acting for it. Access
  * the host derives from its own data is not considered here.
  *
  * A grant reaching a user over their own account ([[Resource.user]]) confers
  * nothing on them, as their powers over it come from being signed in as it:
  * they hold no access over it here, and are never among its holders.
  *
  * @param groups
  *   The store of groups, which decides the principals each user acts as.
  *
  * @param grants
  *   The store of grants.
  */
final class Permissions(groups: GroupStore, grants: GrantStore):

  /**
    * Resolves the highest stored access a user holds over a resource.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param resource
    *   The resource to resolve access over.
    *
    * @return
    *   An effect producing the highest level reaching the user, or `None` if no
    *   grant does.
    */
  def access(user: Long, resource: Resource): IO[Option[Access]] =
    grants.run(accessAt(user, resource))

  /** As [[access]], in the caller's transaction. */
  private[server] def accessAt
    (user: Long, resource: Resource)
    : DBIO[Option[Access]] =
    if Permissions.ownAccount(user, resource) then DBIO.successful(None)
    else groups.principalsAt(user).flatMap(grants.accessOf(_, resource))

  /**
    * Lists the resources of one kind over which a user holds at least the given
    * stored access.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param kind
    *   The kind of resource, as the host names it.
    *
    * @param least
    *   The lowest level of access that counts.
    *
    * @return
    *   An effect producing the identifiers of the resources.
    */
  def accessible(user: Long, kind: String, least: Access): IO[Set[Long]] =
    groups
      .principalsOf(user)
      .flatMap(grants.accessible(_, kind, least))
      .map(ids => if kind == Resource.userKind then ids - user else ids)

  /**
    * Resolves, in one query, the highest stored access a user holds over every
    * resource of one kind that a grant reaching them names.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param kind
    *   The kind of resource, as the host names it.
    *
    * @return
    *   An effect producing each resource's identifier mapped to the highest
    *   level the user holds over it.
    */
  def levels(user: Long, kind: String): IO[Map[Long, Access]] =
    grants.run(levelsAt(user, kind))

  /**
    * As [[levels]], in the caller's transaction, so that a host can read them
    * together with what it locks.
    */
  def levelsAt(user: Long, kind: String): DBIO[Map[Long, Access]] = groups
    .principalsAt(user)
    .flatMap(grants.levelsAt(_, kind))
    .map(levels => if kind == Resource.userKind then levels - user else levels)

  /**
    * Finds every user whom stored access of at least the given level over a
    * resource reaches: its personal grantees, the members of any group it is
    * granted to or of a group nested inside one, and, if it is granted to the
    * system, everyone acting for it. The reverse of [[access]].
    *
    * @param resource
    *   The resource whose holders to find.
    *
    * @param least
    *   The lowest level of access that counts.
    *
    * @return
    *   An effect producing the identifiers of the users.
    */
  def holders
    (
      resource: Resource,
      least: Access = Access.View,
    )
    : IO[Set[Long]] = grants.run(holding(resource, least))

  /** As [[holders]], in the caller's transaction. */
  private[server] def holding
    (
      resource: Resource,
      least: Access = Access.View,
    )
    : DBIO[Set[Long]] = grants
    .granted(resource)
    .map(_.filter(_.access.includes(least)).map(_.principal))
    .flatMap(groups.usersOf)
    .map(users =>
      if resource.kind == Resource.userKind then users - resource.id else users,
    )

object Permissions:

  /** Whether a resource is the user's own account. */
  private[server] def ownAccount(user: Long, resource: Resource): Boolean =
    resource == Resource.user(user)
