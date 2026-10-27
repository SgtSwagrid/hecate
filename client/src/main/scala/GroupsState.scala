package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  Access, GroupDetails, Invitation, Invitee, JoinableGroup, ManagedGroup,
  Membership, Principal, User,
}
import com.raquo.laminar.api.L.*
import io.circe.Decoder
import io.circe.parser.decode
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * A browser-side store of the signed-in user's groups, memberships,
  * invitations and joinable groups, driving the endpoints under `/api/groups`
  * and `/api/invitations`. Every command refetches the lists, as does any
  * change of signed-in user, and a refused read leaves its list empty.
  *
  * @param auth
  *   The sign-in state whose user the groups belong to.
  *
  * @param wording
  *   The wording of this state's own messages. The server's refusals arrive
  *   already worded.
  */
final class GroupsState
  (
    auth: AuthState,
    wording: Wording = Wording.english,
  ):

  // Bound to the page, not a view, so that every request arrives whether or not
  // anything is on screen.
  private given Owner = unsafeWindowOwner

  private val errorVar: Var[Option[String]] = Var(None)

  private val pendingVar: Var[Boolean] = Var(false)

  private val refreshes = new EventBus[Unit]

  private val userId: Signal[Option[Long]] = auth.userId

  /** The groups the signed-in user manages, as a flat list. */
  val managed: Signal[List[ManagedGroup]] = listed[ManagedGroup]("/api/groups")

  /**
    * The groups the signed-in user manages, nested, siblings ordered by name. A
    * group nested inside one they do not manage is at the top level.
    */
  val forest: Signal[List[GroupTree]] = managed.map(GroupsState.nest)

  /**
    * The principals the signed-in user acts as: themselves, every group they
    * belong to, and the system if they act for it.
    */
  val principals: Signal[List[Principal]] = listed[Principal]("/api/principals")

  /** Whether the signed-in user acts for the system. */
  val system: Signal[Boolean] = principals.map(_.contains(Principal.System))

  /** The groups the signed-in user is a member of but does not manage. */
  val memberships: Signal[List[Membership]] =
    listed[Membership]("/api/groups/mine")
      .combineWith(managed)
      .mapN: (mine, manages) =>
        val managedIds = manages.map(_.group.id).toSet
        mine.filterNot(joined => managedIds(joined.group.id))

  /** The pending invitations sent to the signed-in user. */
  val invitations: Signal[List[Invitation]] =
    listed[Invitation]("/api/invitations")

  /**
    * The groups the signed-in user may ask to join or has asked to join,
    * ordered by name.
    */
  val joinable: Signal[List[JoinableGroup]] =
    listed[JoinableGroup]("/api/groups/joinable")

  /** The number of requests to join the signed-in user's groups. */
  val requestCount: Signal[Int] = managed.map(
    _.filter(_.access.includes(Access.Edit)).map(_.applicants.size).sum,
  )

  /** The reason the last command was refused, if any. */
  val error: Signal[Option[String]] = errorVar.signal

  /** Whether a command is in flight. Refetches do not count. */
  val pending: Signal[Boolean] = pendingVar.signal

  /** Refetches every list, emptying those whose requests are refused. */
  def refresh(): Unit = refreshes.emit(())

  /**
    * Creates a group.
    *
    * @param name
    *   The name of the group.
    *
    * @param parent
    *   The identifier of the group to nest it inside, or `None` for the top
    *   level.
    */
  def create(name: String, parent: Option[Long] = None): Unit =
    perform(Fetch.post(
      "/api/groups",
      body = GroupDetails(name, parent),
    ))

  /**
    * Renames a group, leaving its place in the hierarchy alone.
    *
    * @param group
    *   The group to rename.
    *
    * @param name
    *   The new name.
    *
    * @param refused
    *   The callback given the reason if this rename, and not another command,
    *   is refused.
    */
  def rename
    (
      group: ManagedGroup,
      name: String,
      refused: String => Unit = _ => (),
    )
    : Unit = perform(
    update(
      group.group.id,
      GroupDetails(name, group.group.parentId),
    ),
    refused,
  )

  /**
    * Moves a group inside another, or to the top level.
    *
    * @param group
    *   The group to move.
    *
    * @param parent
    *   The identifier of the new parent, or `None` for the top level.
    */
  def move
    (
      group: ManagedGroup,
      parent: Option[Long],
    )
    : Unit = perform(update(
    group.group.id,
    GroupDetails(group.group.name, parent),
  ))

  /**
    * Deletes a group and every group nested inside it that the user owns too.
    *
    * @param group
    *   The identifier of the group.
    */
  def delete(group: Long): Unit = perform(Fetch.delete(s"/api/groups/$group"))

  /**
    * Invites a user to a group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param username
    *   The username of the user to invite.
    */
  def invite(group: Long, username: String): Unit = perform(Fetch.post(
    s"/api/groups/$group/invitations",
    body = Invitee(username),
  ))

  /**
    * Removes a member from a group, cancels their invitation, or declines their
    * request to join.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param person
    *   The identifier of the person.
    */
  def remove(group: Long, person: Long): Unit =
    perform(Fetch.delete(s"/api/groups/$group/members/$person"))

  /**
    * Accepts an invitation, joining its group.
    *
    * @param invitation
    *   The identifier of the invitation.
    */
  def accept(invitation: Long): Unit =
    perform(Fetch.post(s"/api/invitations/$invitation/accept"))

  /**
    * Declines an invitation, deleting it.
    *
    * @param invitation
    *   The identifier of the invitation.
    */
  def decline(invitation: Long): Unit =
    perform(Fetch.post(s"/api/invitations/$invitation/decline"))

  /**
    * Withdraws the signed-in user from a group.
    *
    * @param group
    *   The identifier of the group.
    */
  def leave(group: Long): Unit =
    perform(Fetch.delete(s"/api/groups/$group/membership"))

  /**
    * Makes the signed-in user a member of a group they may change.
    *
    * @param group
    *   The identifier of the group.
    */
  def join(group: Long): Unit =
    perform(Fetch.put(s"/api/groups/$group/membership"))

  /**
    * Asks to join a group. Joins at once a group the user is invited to or may
    * change.
    *
    * @param group
    *   The identifier of the group.
    */
  def request(group: Long): Unit =
    perform(Fetch.put(s"/api/groups/$group/request"))

  /**
    * Withdraws the signed-in user's request to join a group.
    *
    * @param group
    *   The identifier of the group.
    */
  def withdraw(group: Long): Unit =
    perform(Fetch.delete(s"/api/groups/$group/request"))

  /**
    * Admits to a group someone who has asked to join it.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param person
    *   The identifier of the person.
    */
  def admit(group: Long, person: Long): Unit =
    perform(Fetch.put(s"/api/groups/$group/members/$person"))

  /**
    * Makes a group public, or private again.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param public
    *   Whether the group is to be public.
    */
  def setPublic(group: Long, public: Boolean): Unit = perform(Fetch.put(
    s"/api/groups/$group/public",
    body = public,
  ))

  /**
    * Gives a group an invite link unless it has one.
    *
    * @param group
    *   The identifier of the group.
    */
  def link(group: Long): Unit =
    perform(Fetch.put(s"/api/groups/$group/invite-link"))

  /**
    * Replaces a group's invite link with a new one, ending the old.
    *
    * @param group
    *   The identifier of the group.
    */
  def relink(group: Long): Unit =
    perform(Fetch.post(s"/api/groups/$group/invite-link"))

  /**
    * Turns off a group's invite link.
    *
    * @param group
    *   The identifier of the group.
    */
  def unlink(group: Long): Unit =
    perform(Fetch.delete(s"/api/groups/$group/invite-link"))

  /** Discards the last error. */
  def clearError(): Unit = errorVar.set(None)

  private def update
    (group: Long, details: GroupDetails)
    : EventStream[Response] = Fetch.put(s"/api/groups/$group", body = details)

  private def listed[X : Decoder](url: String): Signal[List[X]] = userId
    .flatMapSwitch:
      case None    => Val(List.empty[X])
      case Some(_) => EventStream
          .merge(
            EventStream.fromValue(()),
            refreshes.events,
          )
          .flatMapSwitch(_ => read[X](url))
          .startWith(List.empty)
    .observe

  private def read[X : Decoder](url: String): EventStream[List[X]] = Outcome
    .of(Fetch.get(url))
    .map:
      case Outcome.Answered(response) => decode[List[X]](response.data)
          .getOrElse(List.empty)
      case _ => List.empty

  private def perform
    (
      request: EventStream[Response],
      refused: String => Unit = _ => (),
    )
    : Unit =
    errorVar.set(None)
    Outcome.tracked(auth.outcome(request), pendingVar): outcome =>
      outcome match
        case Outcome.Answered(_)     => errorVar.set(None)
        case Outcome.Refused(reason) =>
          errorVar.set(Some(reason))
          refused(reason)
          // Not a refusal, so `refused` is not called.
        case Outcome.Unreachable(_) => errorVar.set(Some(wording.unreachable))
      refresh()

object GroupsState:

  /**
    * Nests a flat group list, siblings ordered by name. A group whose parent is
    * missing, or which a cycle would bury, is put at the top level, so that no
    * group is lost.
    */
  private[client] def nest(groups: List[ManagedGroup]): List[GroupTree] =
    val ordered = groups.sortBy(_.group.name)
    val known   = ordered.map(_.group.id).toSet
    plant(
      ordered.groupBy(_.group.parentId),
    )(ordered.filterNot(_.group.parentId.exists(known)) ++ ordered)

  private def plant
    (children: Map[Option[Long], List[ManagedGroup]])
    (roots: List[ManagedGroup])
    : List[GroupTree] = roots
    .foldLeft((List.empty[GroupTree], Set.empty[Long])):
      case ((forest, shown), view) if shown(view.group.id) => (forest, shown)
      case ((forest, shown), view)                         =>
        val tree = grow(children)(view, Set.empty)
        (tree :: forest, shown ++ tree.flatten.map(_.view.group.id))
    ._1
    .reverse

  // Skips enclosing groups, so that a cycle cannot recurse forever.
  private def grow
    (children: Map[Option[Long], List[ManagedGroup]])
    (view: ManagedGroup, enclosing: Set[Long])
    : GroupTree = GroupTree(
    view,
    children
      .getOrElse(Some(view.group.id), List.empty)
      .filterNot(child => enclosing(child.group.id))
      .map(grow(children)(_, enclosing + view.group.id)),
  )

/**
  * A group in the nested view, with the groups inside it.
  *
  * @param view
  *   The group as its managers see it.
  *
  * @param children
  *   The groups nested directly inside this one, ordered by name.
  */
final case class GroupTree
  (
    view: ManagedGroup,
    children: List[GroupTree],
  ):

  /** The trees of this group and every group beneath it, depth first. */
  def flatten: List[GroupTree] = this :: children.flatMap(_.flatten)

  /**
    * Lists this group and every group beneath it, depth first, each with its
    * depth.
    *
    * @param depth
    *   The depth of this group, `0` at the top level.
    *
    * @return
    *   A list of each group paired with its depth.
    */
  def outline(depth: Int = 0): List[(ManagedGroup, Int)] = (view, depth) ::
    children.flatMap(_.outline(depth + 1))

  /**
    * The distinct members of this group and every group beneath it, ordered by
    * username. Invitees are not members.
    */
  def members: List[User] = flatten
    .flatMap(_.view.members)
    .distinctBy(_.id)
    .sortBy(_.username)
