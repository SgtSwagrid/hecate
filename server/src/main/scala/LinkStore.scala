package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.{
  Access, InviteCode, InviteLink, LinkTarget, Resource, User,
}
import java.security.SecureRandom
import slick.dbio.DBIO

/**
  * A store of invite links, each a code (see [[InviteCode]]) leading to a group
  * or a resource. Every action composes into the caller's transaction.
  *
  * At most one link leads to each group and each resource only while the caller
  * locks the target's row before changing its link, as [[GroupStore]] does for
  * groups and the host must for resources. A link stored under a code that is
  * no longer valid, as one drawn by an earlier release, counts as no link.
  *
  * @param tables
  *   The tables the links are stored in.
  */
final class LinkStore(tables: AuthTables):

  import tables.profile.api.*

  /**
    * Finds the link leading to a target.
    *
    * @param target
    *   The group or resource the link leads to; a resource's level is ignored.
    *
    * @return
    *   An action yielding the link, or `None`.
    */
  def to(target: LinkTarget): DBIO[Option[InviteLinkRow]] = leadingTo(target)
    .result
    .map(_.find(row => InviteCode.valid(row.code)))

  /**
    * Finds the codes of the links to some groups.
    *
    * @param groups
    *   The identifiers of the groups.
    *
    * @return
    *   An action yielding each linked group's identifier mapped to its code.
    */
  def toGroups(groups: Seq[Long]): DBIO[Map[Long, String]] = ifAny(groups)(
    Map.empty,
  ): shown =>
    tables
      .inviteLinks
      .filter(_.groupId inSet shown)
      .map(link => (link.groupId, link.code))
      .result
      .map(
        _.collect {
            case (Some(group), code) if InviteCode.valid(code) => group -> code
          }
          .toMap,
      )

  /**
    * Finds the link leading to a resource, as its owners see it.
    *
    * @param resource
    *   The resource the link leads to.
    *
    * @return
    *   An action yielding the link, or `None`.
    */
  def over(resource: Resource): DBIO[Option[InviteLink]] = to(
    LinkTarget.Sharing(resource, Access.View),
  ).map(_.flatMap(row =>
    row
      .target
      .collect { case LinkTarget.Sharing(_, access) =>
        InviteLink(row.code, access)
      },
  ))

  /**
    * Finds a link by its code, read in any case.
    *
    * @param code
    *   The code as given.
    *
    * @return
    *   An action yielding the link and the user who made it, or `None`.
    */
  def find(code: String): DBIO[Option[(InviteLinkRow, User)]] = InviteCode
    .parse(code)
    .fold(DBIO.successful(None))(known =>
      tables
        .inviteLinks
        .filter(_.code === known)
        .join(tables.users)
        .on(_.creatorId === _.id)
        .result
        .headOption
        .map(_.map((row, creator) => (row, creator.toUser))),
    )

  /**
    * Gives a target a link unless it has one. A resource's existing link is set
    * to the target's level of access.
    *
    * @param creator
    *   The identifier of the user making the link, if one is made.
    *
    * @param target
    *   The place the link leads, and for a resource the access it grants.
    *
    * @return
    *   An action yielding the link's code.
    */
  def ensure(creator: Long, target: LinkTarget): DBIO[String] = to(target)
    .flatMap:
      case Some(row) => regrant(target).map(_ => row.code)
      case None      => renew(creator, target)

  /**
    * Replaces a target's link with one of a new code, or makes one.
    *
    * @param creator
    *   The identifier of the user making the link.
    *
    * @param target
    *   The place the link leads, and for a resource the access it grants.
    *
    * @return
    *   An action yielding the new code.
    */
  def renew(creator: Long, target: LinkTarget): DBIO[String] = delete(target)
    .flatMap(_ => create(creator, target))

  /**
    * Turns off the link leading to a target.
    *
    * @param target
    *   The group or resource the link leads to.
    *
    * @return
    *   An action deleting the link, doing nothing if there is none.
    */
  def delete(target: LinkTarget): DBIO[Unit] = leadingTo(target).delete.unit

  /**
    * Turns off every link leading to any of some groups.
    *
    * @param groups
    *   The identifiers of the groups.
    *
    * @return
    *   An action deleting the links.
    */
  def deleteToGroups(groups: Seq[Long]): DBIO[Unit] = ifAny(groups)(()):
    doomed => tables.inviteLinks.filter(_.groupId inSet doomed).delete.unit

  /**
    * Turns off every link a user made.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An action deleting the links.
    */
  def deleteMadeBy(user: Long): DBIO[Unit] = tables
    .inviteLinks
    .filter(_.creatorId === user)
    .delete
    .unit

  /** Runs an action as one transaction, for services that hold no profile. */
  private[server] def atomically[X](action: DBIO[X]): DBIO[X] =
    action.transactionally

  private def regrant(target: LinkTarget): DBIO[Unit] = target match
    case LinkTarget.Joining(_)         => DBIO.unit
    case LinkTarget.Sharing(_, access) =>
      leadingTo(target).map(_.access).update(Some(access.code)).unit

  /** Stores a link under a random free code, giving up after a few tries. */
  private def create
    (
      creator: Long,
      target: LinkTarget,
      tries: Int = LinkStore.tries,
    )
    : DBIO[String] = DBIO
    .unit
    .flatMap(_ => claim(LinkStore.randomCode(), creator, target))
    .flatMap:
      case Some(code)        => DBIO.successful(code)
      case None if tries > 1 => create(creator, target, tries - 1)
      case None              =>
        DBIO.failed(IllegalStateException("No free invite code was found."))

  private def claim
    (
      code: String,
      creator: Long,
      target: LinkTarget,
    )
    : DBIO[Option[String]] = tables
    .inviteLinks
    .filter(_.code === code)
    .exists
    .result
    .flatMap:
      case true  => DBIO.successful(None)
      case false => (tables.inviteLinks +=
          InviteLinkRow.of(code, creator, target)).map(_ => Some(code))

  private def leadingTo(target: LinkTarget) = target match
    case LinkTarget.Joining(group) =>
      tables.inviteLinks.filter(_.groupId === group)
    case LinkTarget.Sharing(resource, _) => tables
        .inviteLinks
        .filter(link =>
          link.resourceKind === resource.kind && link.resourceId === resource.id,
        )

object LinkStore:

  private val tries = 10

  private val random = SecureRandom()

  /** A code drawn uniformly from every valid code, by rejection sampling. */
  private def randomCode(): String = Iterator
    .continually(draw())
    .find(InviteCode.valid)
    .get

  private def draw(): String = String(Array.fill(InviteCode.length)(
    InviteCode.alphabet(random.nextInt(InviteCode.alphabet.length)),
  ))
