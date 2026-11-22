package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{
  Access, AuthRefusal, Grant, InviteLink, LinkTarget, Principal, Resource, User,
}
import slick.dbio.DBIO

/**
  * A service that lets a resource's owners see and change who holds it, and
  * manage its invite link. A resource is never left with nobody holding
  * [[Access.Own]], no link grants [[Access.Own]], and only a principal the
  * owner may address ([[GroupStore.mayAddress]]) is granted anything. Nobody is
  * granted anything over their own account ([[Resource.user]]), and an account
  * has no invite link, as following one would let anyone manage it: making one
  * is refused as if the account were missing. Every change locks the resource's
  * row first, through `resources`, so that two owners giving up ownership at
  * once cannot each see the other still holding it.
  *
  * @param grants
  *   The store of grants.
  *
  * @param links
  *   The store of invite links.
  *
  * @param groups
  *   The store of groups.
  *
  * @param db
  *   The database the stores use.
  *
  * @param resources
  *   The lookup of a resource's name that locks its row until the transaction
  *   ends, yielding `None` for a missing resource, as for [[LinkService]].
  *
  * @param access
  *   The host's resolution of a user's access to a resource, or `None` for
  *   stored grants alone ([[Permissions.access]]).
  *
  * @param report
  *   The handler of failures whose detail must not reach the client.
  *
  * @param affected
  *   The hook told whom each committed change concerns (see [[Affected]]).
  */
final class SharingService
  (
    grants: GrantStore,
    links: LinkStore,
    groups: GroupStore,
    db: Transactor,
    resources: Resource => DBIO[Option[String]],
    access: Option[(User, Resource) => IO[Option[Access]]] = None,
    report: Throwable => IO[Unit] = error => Console[IO].printStackTrace(error),
    affected: Affected => IO[Unit] = _ => IO.unit,
  ):

  private val failures = Failures(report)

  private val permissions = Permissions(groups, grants)

  private val accessOf: (User, Resource) => IO[Option[Access]] = access
    .getOrElse((user, resource) => permissions.access(user.id, resource))

  /**
    * Lists the grants over a resource the user owns.
    *
    * @param user
    *   The signed-in user.
    *
    * @param resource
    *   The resource.
    *
    * @return
    *   An answer with the grants, or a refusal.
    */
  def list(user: User, resource: Resource): Answer[List[Grant]] =
    owning(user, resource)(grants.over(resource))

  /**
    * Grants a principal exactly the given access over a resource the user owns,
    * replacing whatever they held.
    *
    * @param user
    *   The signed-in user.
    *
    * @param resource
    *   The resource.
    *
    * @param principal
    *   The user or group to grant access to.
    *
    * @param level
    *   The access to grant.
    *
    * @return
    *   An answer with nothing, or a refusal.
    */
  def grant
    (
      user: User,
      resource: Resource,
      principal: Principal,
      level: Access,
    )
    : Answer[Unit] = checked(
    Option.when(
      principal == Principal.Person(resource.id) &&
      resource.kind == Resource.userKind,
    )(AuthRefusal.ManagingSelf),
  )(owning(user, resource)(
    groups
      .mayAddress(user.id, principal)
      .flatMap(addressable =>
        if addressable then change(resource, principal, Some(level))
        else IO.raiseError(AuthProblem(AuthRefusal.PrincipalMissing)),
      ),
  ))

  /**
    * Withdraws a principal's access over a resource the user owns. It asks
    * nothing of the principal, so that an owner may remove whoever followed the
    * invite link.
    *
    * @param user
    *   The signed-in user.
    *
    * @param resource
    *   The resource.
    *
    * @param principal
    *   The user or group to withdraw access from.
    *
    * @return
    *   An answer with nothing, or a refusal.
    */
  def revoke
    (
      user: User,
      resource: Resource,
      principal: Principal,
    )
    : Answer[Unit] = owning(user, resource)(change(resource, principal, None))

  /**
    * Reads the invite link of a resource the user owns.
    *
    * @param user
    *   The signed-in user.
    *
    * @param resource
    *   The resource.
    *
    * @return
    *   An answer with the link, if any, or a refusal.
    */
  def link(user: User, resource: Resource): Answer[Option[InviteLink]] =
    owning(user, resource)(db.run(links.over(resource)))

  /**
    * Gives a resource the user owns an invite link granting the given access,
    * or makes its link grant it.
    *
    * @param user
    *   The signed-in user.
    *
    * @param resource
    *   The resource.
    *
    * @param level
    *   The access the link grants, never [[Access.Own]].
    *
    * @return
    *   An answer with the link, or a refusal.
    */
  def setLink
    (
      user: User,
      resource: Resource,
      level: Access,
    )
    : Answer[InviteLink] = checked(
    SharingService.unlinkable(resource),
    Option.when(level == Access.Own)(AuthRefusal.OwnershipByLink),
  )(owning(user, resource)(relinking(resource)(
    links
      .ensure(
        user.id,
        LinkTarget.Sharing(resource, level),
      )
      .map(InviteLink(_, level)),
  )))

  /**
    * Replaces the invite link of a resource the user owns with a new code,
    * ending the old, and granting the same access, or `View` if it had none.
    *
    * @param user
    *   The signed-in user.
    *
    * @param resource
    *   The resource.
    *
    * @return
    *   An answer with the new link, or a refusal.
    */
  def relink(user: User, resource: Resource): Answer[InviteLink] = checked(
    SharingService.unlinkable(resource),
  )(owning(user, resource)(relinking(resource)(
    links
      .over(resource)
      .map(_.fold(Access.View)(_.access))
      .flatMap(level =>
        links
          .renew(
            user.id,
            LinkTarget.Sharing(resource, level),
          )
          .map(InviteLink(_, level)),
      ),
  )))

  /**
    * Turns off the invite link of a resource the user owns.
    *
    * @param user
    *   The signed-in user.
    *
    * @param resource
    *   The resource.
    *
    * @return
    *   An answer with nothing, or a refusal.
    */
  def unlink(user: User, resource: Resource): Answer[Unit] =
    owning(user, resource)(relinking(resource)(
      links.delete(LinkTarget.Sharing(resource, Access.View)),
    ))

  /** Runs an action if the user owns the resource, else refuses it. */
  private def owning[X]
    (user: User, resource: Resource)
    (action: => IO[X])
    : Answer[X] = failures.attemptRefusable(
    accessOf(user, resource).flatMap:
      case None => IO.pure(Left(AuthRefusal.ResourceMissing(resource.kind)))
      case Some(level) if !level.includes(Access.Own) =>
        IO.pure(Left(AuthRefusal.NotOwner(resource.kind)))
      case Some(_) => action.map(Right(_)),
  )

  /**
    * Sets or withdraws a principal's access, unless that would leave the
    * resource without an owner. Whom the grants reached before is read under
    * the lock, and only when the change takes access from someone.
    */
  private def change
    (
      resource: Resource,
      principal: Principal,
      level: Option[Access],
    )
    : IO[Unit] = locking(resource)(
    for
      held <- grants.granted(resource)
      _    <- refuseUnless(
        !SharingService.orphans(held, principal, level),
        AuthRefusal.LastOwner(resource.kind),
      )
      formerly <-
        if SharingService.takes(held, principal, level) then
          permissions.holding(resource)
        else DBIO.successful(Set.empty[Long])
      _ <- level.fold(grants.revoke(resource, principal))(granted =>
        grants.grant(Grant(resource, principal, granted)),
      )
    yield formerly,
  ).flatMap(formerly => told(resource, formerly))

  /** Changes a resource's invite link under its lock. */
  private def relinking[X](resource: Resource)(change: DBIO[X]): IO[X] =
    locking(resource)(change).flatTap(_ => told(resource, Set.empty))

  /** Runs a change under the resource's row lock, refused if it is missing. */
  private def locking[X](resource: Resource)(change: DBIO[X]): IO[X] =
    db.run(links.atomically(
      resources(resource)
        .flatMap(required(
          _,
          AuthRefusal.ResourceMissing(resource.kind),
        ))
        .flatMap(_ => change),
    ))

  /**
    * Tells the host whom a committed change to a resource's grants concerns,
    * reporting any failure.
    */
  private def told(resource: Resource, formerly: Set[Long]): IO[Unit] = groups
    .grantsChanged(resource, formerly)
    .flatMap(affected)
    .handleErrorWith(report)

object SharingService:

  /** The refusal of an invite link to a kind of resource that has none. */
  private def unlinkable(resource: Resource): Option[AuthRefusal] = Option.when(
    resource.kind == Resource.userKind,
  )(AuthRefusal.ResourceMissing(resource.kind))

  /** Whether the change lowers or withdraws what the principal held. */
  private[server] def takes
    (
      held: List[Grant],
      principal: Principal,
      level: Option[Access],
    )
    : Boolean = held
    .filter(_.principal == principal)
    .exists(before => !level.exists(_.includes(before.access)))

  /** Whether the change would leave the resource with nobody holding `Own`. */
  private[server] def orphans
    (
      held: List[Grant],
      principal: Principal,
      level: Option[Access],
    )
    : Boolean =
    val owners = held.filter(_.access == Access.Own).map(_.principal).toSet
    val after  =
      if level.contains(Access.Own) then owners + principal
      else owners - principal
    after.isEmpty
