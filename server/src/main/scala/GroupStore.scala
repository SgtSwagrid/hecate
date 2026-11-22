package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, AuthRefusal, Grant, Group, GroupDetails, Holder, Invitation,
  JoinableGroup, LinkTarget, ManagedGroup, Manager, Membership, Principal,
  Resource, User,
}
import slick.dbio.DBIO

/**
  * A store of user groups, their memberships, invitations and requests to join,
  * and of the principals each user acts as.
  *
  * A group is a resource ([[Resource.group]]), and grants over it decide who
  * manages it: [[Access.View]] to see who is in it, [[Access.Edit]] to change
  * that and the group itself, and [[Access.Own]] to delete it and choose who
  * manages it, which [[SharingService]] does as for any resource. Its creator
  * first holds `Own`. Below the level an action needs, a group is reported
  * missing. Its direct members see it, its owners and one another, but only its
  * managers see who is invited or asking to join. Nobody joins without both
  * their own and a manager's agreement: by accepting an invitation, by having a
  * request admitted, or by following an invite link. A request may only be made
  * of a group the asker can see, a public one or one nested inside a group they
  * are a member of; any other is reported as missing. A group never contains
  * itself, and every walk of the tree visits each group once.
  *
  * Every change to a group's members, invitations, requests or invite link
  * first locks the group's row, as its check-then-insert races under
  * `READ COMMITTED` otherwise. Any new change of this kind must take the same
  * lock. Several groups are locked in one statement in ascending order of
  * identifier, so that two transactions cannot deadlock.
  *
  * @param tables
  *   The tables to store the groups in.
  *
  * @param db
  *   The database to run queries against.
  *
  * @param cascade
  *   The hook deleting the host's rows for groups about to be deleted, run in
  *   the same transaction before the groups go. The groups' grants are revoked
  *   by this store.
  *
  * @param resources
  *   The lookup of a resource's name that locks its row until the transaction
  *   ends, yielding `None` for a missing resource, as for [[LinkService]]. A
  *   deleted group's resources pass to whoever deleted it under these locks.
  *   Groups are looked up by this store itself (see [[named]]). The default
  *   locks nothing and finds every resource.
  *
  * @param operators
  *   The email addresses of the users who act for the system
  *   ([[Principal.System]]), matched in any case, and only once confirmed.
  */
final class GroupStore
  (
    tables: AuthTables,
    db: Transactor,
    cascade: Seq[Principal] => DBIO[Unit] = _ => DBIO.unit,
    resources: Resource => DBIO[Option[String]] = GroupStore.everywhere,
    operators: Set[String] = Set.empty,
  ):

  import tables.profile.api.*

  private type Pairs = Query[(Rep[Long], Rep[Long]), (Long, Long), Seq]

  private val grants = GrantStore(tables, db)

  private val links = LinkStore(tables)

  private val permissions = Permissions(this, grants)

  private val acting = operators.map(EmailAddress.normalise)

  private val memberPairs: Pairs = tables
    .members
    .map(row => (row.groupId, row.userId))

  private val invitationPairs: Pairs = tables
    .invitations
    .map(row => (row.groupId, row.userId))

  private val requestPairs: Pairs = tables
    .requests
    .map(row => (row.groupId, row.userId))

  /**
    * The lookup of a resource's name that locks its row: a group's or an
    * account's by this store, and any other by the host's `resources`. Pass
    * this to [[SharingService]] and [[LinkService]], so that groups and
    * accounts are shared like the host's resources.
    */
  val named: Resource => DBIO[Option[String]] = resource =>
    if resource.kind == Resource.groupKind then
      lock(Seq(resource.id)).map(_.headOption.map(_.name))
    else if resource.kind == Resource.userKind then
      tables.lockUser(resource.id).map(_.map(_.username))
    else resources(resource)

  /**
    * Lists the principals a user acts as: themselves, every group they belong
    * to ([[enclosing]]), and [[Principal.System]] if their confirmed email
    * address is among the operators'.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the principals, the user first.
    */
  def principalsOf(user: Long): IO[List[Principal]] = db.run(principalsAt(user))

  /** As [[principalsOf]], in the caller's transaction. */
  private[server] def principalsAt(user: Long): DBIO[List[Principal]] =
    for
      groups <- enclosingAt(user)
      system <- actsForSystem(user)
    yield (Principal.Person(user) :: groups.map(Principal.Group(_))) ++
      Option.when(system)(Principal.System)

  private def actsForSystem(user: Long): DBIO[Boolean] =
    if acting.isEmpty then DBIO.successful(false)
    else
      tables
        .users
        .filter(_.id === user)
        .map(_.email)
        .result
        .headOption
        .map(_.flatten.exists(acting))

  /**
    * Finds every user some principals reach: the people themselves, the members
    * of the groups or of any group nested inside them, and everyone acting for
    * the system. In the caller's transaction.
    */
  private[server] def usersOf(principals: Seq[Principal]): DBIO[Set[Long]] =
    for
      members <- membersBeneath(principals.collect { case Principal.Group(id) =>
        id
      })
      system <-
        if principals.contains(Principal.System) then operatorIds
        else DBIO.successful(Set.empty[Long])
    yield members.toSet ++ system ++ principals.collect {
      case Principal.Person(id) => id
    }

  private def operatorIds: DBIO[Set[Long]] =
    ifAny(acting.toSeq)(Set.empty[Long]): addresses =>
      tables.users.filter(_.email inSet addresses).map(_.id).result.map(_.toSet)

  /**
    * Lists the groups a user manages, holding at least [[Access.View]] over
    * them, each with their access, direct members, invitees, applicants and,
    * for those they may change, invite link.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the groups, in order of creation.
    */
  def managed(user: Long): IO[List[ManagedGroup]] = db.run:
    for
      principals <- principalsAt(user)
      levels     <- grants.levelsAt(principals, Resource.groupKind)
      groups     <- rowsOf(levels.keys.toSeq)
      ids = groups.map(_.id)
      enrolled   <- usersIn(memberPairs, ids)
      invited    <- usersIn(invitationPairs, ids)
      applicants <- usersIn(requestPairs, ids)
      codes      <- links.toGroups(ids)
    yield groups
      .map(group =>
        val access = levels(group.id)
        ManagedGroup(
          group.toGroup,
          access,
          enrolled.getOrElse(group.id, List.empty),
          invited.getOrElse(group.id, List.empty),
          applicants.getOrElse(group.id, List.empty),
          codes.get(group.id).filter(_ => access.includes(Access.Edit)),
        ),
      )
      .toList

  /** The rows of some groups, in ascending order. */
  private def rowsOf(groups: Seq[Long]): DBIO[Seq[GroupRow]] =
    ifAny(groups)(Seq.empty[GroupRow]): wanted =>
      tables.groups.filter(_.id inSet wanted).sortBy(_.id).result

  /**
    * Lists the groups a user is directly a member of, each with its owners, its
    * direct members and the groups enclosing it, which the user belongs to
    * through it. Those have no membership of their own, as the user cannot
    * leave a group they never joined, and their members stay unseen.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the memberships.
    */
  def memberships(user: Long): IO[List[Membership]] = db.run:
    for
      joined <- membershipsOf(user).map(_._2).sortBy(_.id).result
      ids = joined.map(_.id)
      enrolled <- usersIn(memberPairs, ids)
      owners   <- ownersOf(ids)
      parents  <- ancestry(ids)
      named <- rowsOf(ids.flatMap(GroupStore.above(_, parents)).distinct).map(
        _.map(row => row.id -> row).toMap,
      )
    yield joined
      .map(group =>
        Membership(
          group.toGroup,
          owners.getOrElse(group.id, List.empty),
          enrolled.getOrElse(group.id, List.empty),
          GroupStore
            .above(group.id, parents)
            .reverse
            .flatMap(named.get)
            .map(_.toGroup),
        ),
      )
      .toList

  /**
    * Whoever holds `Own` over each group, the system first, then people by
    * username, then groups by name; groups nobody owns are absent.
    */
  private def ownersOf(groups: Seq[Long]): DBIO[Map[Long, List[Holder]]] =
    managersAt(Resource.groupKind, groups, Access.Own).map(
      _.view.mapValues(_.map(_.holder).distinct).toMap,
    )

  /**
    * Whoever holds at least the given access over each of some resources of one
    * kind, named, the highest access first, then the system, people by username
    * and groups by name; resources nobody holds are absent. In the caller's
    * transaction.
    */
  private[server] def managersAt
    (
      kind: String,
      ids: Seq[Long],
      least: Access,
    )
    : DBIO[Map[Long, List[Manager]]] =
    for
      holding <- grants.grantedAt(kind, ids, least)
      held = holding.map(_.principal)
      people <- usernames(held.collect { case Principal.Person(id) => id })
      named  <- namesAt(held.collect { case Principal.Group(id) => id })
    yield holding
      .groupMap(_.resource.id)(grant =>
        Manager(
          Holder(
            grant.principal,
            grant.principal match
              case Principal.Person(id) => people.get(id)
              case Principal.Group(id)  => named.get(id)
              case Principal.System     => None,
          ),
          grant.access,
        ),
      )
      .view
      .mapValues(
        _.distinct
          .sortBy(manager =>
            (-manager.access.ordinal, GroupStore.holderOrder(manager.holder)),
          ),
      )
      .toMap

  private def usernames(users: Seq[Long]): DBIO[Map[Long, String]] = ifAny(
    users.distinct,
  )(Map.empty[Long, String]): wanted =>
    tables
      .users
      .filter(_.id inSet wanted)
      .map(user => (user.id, user.username))
      .result
      .map(_.toMap)

  /** The direct members of each group, by username; empty groups are absent. */
  private def usersIn
    (pairs: Pairs, groups: Seq[Long])
    : DBIO[Map[Long, List[User]]] = ifAny(groups)(Map.empty): shown =>
    pairs
      .filter(_._1 inSet shown)
      .join(tables.users)
      .on(_._2 === _.id)
      .map((pair, user) => (pair._1, user))
      .result
      .map(GroupStore.byGroup)

  /** Never asks the database about no groups. */
  private def pairsIn
    (pairs: Pairs, groups: Seq[Long])
    : DBIO[Seq[(Long, Long)]] =
    ifAny(groups)(Seq.empty)(shown => pairs.filter(_._1 inSet shown).result)

  private def groupsWith(pairs: Pairs, user: Long): DBIO[Set[Long]] = pairs
    .filter(_._2 === user)
    .map(_._1)
    .result
    .map(_.toSet)

  /**
    * Lists the pending invitations sent to a user, withholding each group's
    * parent.
    *
    * @param user
    *   The identifier of the invitee.
    *
    * @return
    *   An effect producing the invitations, each with its group and whoever
    *   sent it.
    */
  def invitations(user: Long): IO[List[Invitation]] = db
    .run(invitationsOf(Seq(user)))
    .map(_.getOrElse(user, List.empty))

  /**
    * The pending invitations sent to each of some users, in order of sending;
    * users with none are absent. In the caller's transaction.
    */
  private[server] def invitationsOf
    (users: Seq[Long])
    : DBIO[Map[Long, List[Invitation]]] = ifAny(users.distinct)(
    Map.empty[Long, List[Invitation]],
  ): invitees =>
    tables
      .invitations
      .filter(_.userId inSet invitees)
      .join(tables.groups)
      .on(_.groupId === _.id)
      .joinLeft(tables.users)
      .on(_._1.inviterId === _.id)
      .sortBy(_._1._1.id)
      .result
      .map(
        _.toList
          .groupMap(_._1._1.userId) { case ((invitation, group), inviter) =>
            Invitation(
              invitation.id,
              Group(group.id, group.name),
              inviter.map(_.toUser),
            )
          },
      )

  /**
    * Lists the groups a user may ask to join: every public group and every
    * group nested inside one they are a member of, except those they manage,
    * belong to or are invited to. Groups they have asked to join are always
    * included, so they can withdraw the request. A parent they cannot see is
    * withheld.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the groups by name, each with its owners and whether
    *   the user has asked already.
    */
  def joinable(user: Long): IO[List[JoinableGroup]] = db.run:
    for
      direct     <- directOf(user)
      nested     <- descendants(direct)
      principals <- principalsAt(user)
      managing   <- grants.accessibleAt(
        principals,
        Resource.groupKind,
        Access.Edit,
      )
      asked   <- groupsWith(requestPairs, user)
      invited <- groupsWith(invitationPairs, user)
      found   <- findable(nested ++ asked).sortBy(_.name).result
      joined  = direct.toSet
      offered = found.filterNot(group =>
        joined(group.id) || invited(group.id) || managing(group.id),
      )
      owners <- ownersOf(offered.map(_.id))
      // The groups they joined are nested too, as a walk includes its roots.
      seen = nested ++ offered.map(_.id)
    yield offered
      .map(group =>
        JoinableGroup(
          group.toGroup.copy(parentId = group.parentId.filter(seen)),
          owners.getOrElse(group.id, List.empty),
          asked(group.id),
        ),
      )
      .toList

  private def findable(groups: Set[Long]) = tables
    .groups
    .filter(group => group.public || (group.id inSet groups))

  /** The existing groups a user is directly a member of. */
  private def directOf(user: Long): DBIO[Seq[Long]] = membershipsOf(user)
    .map(_._2.id)
    .result

  /**
    * Lists every group a user effectively belongs to: their memberships plus
    * every ancestor of those. Membership propagates upwards only, so anything
    * addressed to a group reaches the members of the groups nested in it, and
    * never the reverse. Invitations and requests count for nothing.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the groups' identifiers in ascending order.
    */
  def enclosing(user: Long): IO[List[Long]] = db.run(enclosingAt(user))

  private def enclosingAt(user: Long): DBIO[List[Long]] =
    for
      direct  <- directOf(user)
      parents <- ancestry(direct)
    yield GroupStore.withAncestors(direct, parents)

  /**
    * Every given group and each of its ancestors mapped to its parent, climbing
    * one level per query. Missing groups are absent, and a group is asked for
    * once, so a cycle ends.
    */
  private def ancestry(groups: Seq[Long]): DBIO[Map[Long, Option[Long]]] =
    def climb
      (
        wanted: Set[Long],
        known: Map[Long, Option[Long]],
      )
      : DBIO[Map[Long, Option[Long]]] =
      ifAny((wanted -- known.keySet).toSeq)(known): asked =>
        tables
          .groups
          .filter(_.id inSet asked)
          .map(group => (group.id, group.parentId))
          .result
          .flatMap(rows => climb(rows.flatMap(_._2).toSet, known ++ rows))
    climb(groups.toSet, Map.empty)

  /**
    * The groups with every group nested beneath them, descending one level per
    * query.
    */
  private def descendants(groups: Seq[Long]): DBIO[Set[Long]] =
    def descend(frontier: Set[Long], seen: Set[Long]): DBIO[Set[Long]] = ifAny(
      frontier.toSeq,
    )(seen): parents =>
      tables
        .groups
        .filter(_.parentId inSet parents)
        .map(_.id)
        .result
        .flatMap(children =>
          val fresh = children.toSet -- seen
          descend(fresh, seen ++ fresh),
        )
    descend(groups.toSet, groups.toSet)

  /**
    * Lists every member of some groups or of any group nested inside them: the
    * reverse of [[enclosing]].
    *
    * @param groups
    *   The identifiers of the groups.
    *
    * @return
    *   An effect producing the users' identifiers in ascending order.
    */
  def membersWithin(groups: Seq[Long]): IO[List[Long]] =
    if groups.isEmpty then IO.pure(List.empty)
    else db.run(membersBeneath(groups))

  /** As [[membersWithin]], in the caller's transaction. */
  private[server] def membersBeneath(groups: Seq[Long]): DBIO[List[Long]] =
    ifAny(groups)(List.empty[Long]): some =>
      for
        within  <- descendants(some)
        members <- tables
          .members
          .filter(_.groupId inSet within)
          .map(_.userId)
          .distinct
          .sortBy(identity)
          .result
      yield members.toList

  /**
    * Finds everyone who may address a user: whoever holds at least
    * [[Access.Edit]] over a group the user belongs to ([[enclosing]]), as
    * whatever is addressed to a group reaches the groups nested in it.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the users' identifiers.
    */
  def addressersOf(user: Long): IO[Set[Long]] = db.run:
    for
      groups   <- enclosingAt(user)
      managers <- grants.grantedAt(Resource.groupKind, groups, Access.Edit)
      users    <- usersOf(managers.map(_.principal).distinct)
    yield users

  /**
    * Names groups, for a host to say whom something was addressed to.
    *
    * @param groups
    *   The identifiers of the groups.
    *
    * @return
    *   An effect producing the name of each group that still exists, by
    *   identifier.
    */
  def names(groups: Seq[Long]): IO[Map[Long, String]] = db.run(namesAt(groups))

  private def namesAt(groups: Seq[Long]): DBIO[Map[Long, String]] = ifAny(
    groups.distinct,
  )(Map.empty[Long, String]): wanted =>
    tables
      .groups
      .filter(_.id inSet wanted)
      .map(group => (group.id, group.name))
      .result
      .map(_.toMap)

  /**
    * Creates a group, owned by its creator.
    *
    * @param user
    *   The identifier of the creator.
    *
    * @param details
    *   The group's name, and its parent, which the creator must be able to
    *   change.
    *
    * @return
    *   An effect producing the stored group.
    */
  def create(user: Long, details: GroupDetails): IO[Group] =
    // Locked so the parent cannot be deleted from under its new child.
    holding(details.parentId.toSeq): held =>
      for
        _  <- ensureParent(held, user, details.parentId)
        id <- tables.groups.returning(tables.groups.map(_.id)) +=
          GroupRow(0, details.name, details.parentId)
        _ <- grants.grant(Grant(
          Resource.group(id),
          Principal.Person(user),
          Access.Own,
        ))
      yield Group(id, details.name, details.parentId)

  /**
    * Renames or moves a group, refusing a move inside its own subtree. A move
    * needs the new parent to be one the user may change too.
    *
    * @param user
    *   The identifier of a user who may change the group.
    *
    * @param id
    *   The identifier of the group.
    *
    * @param details
    *   The group's new name and parent.
    */
  def update
    (
      user: Long,
      id: Long,
      details: GroupDetails,
    )
    : IO[Unit] = holding(id +: details.parentId.toSeq): held =>
    for
      _ <- ensureLevel(held, user, id, Access.Edit)
      moved =
        !held.exists(row => row.id == id && row.parentId == details.parentId)
      _ <-
        if moved then
          ensureParent(held, user, details.parentId).flatMap(_ =>
            ensureAcyclic(id, details.parentId),
          )
        else DBIO.unit
      _ <- tables
        .groups
        .filter(_.id === id)
        .map(group => (group.name, group.parentId))
        .update((details.name, details.parentId))
    yield ()

  /**
    * Deletes a group the user owns, with every group nested beneath it that
    * they own too, and their memberships, invitations, requests, links, grants
    * and the host's rows. A nested group they do not own takes the deleted
    * group's place in the tree. Whatever the deleted groups alone owned passes
    * to the user, so that nothing is left without an owner.
    *
    * Locks are taken in the order sharing and account deletion take them: the
    * resources the groups own (through `resources`, found before the groups are
    * locked, and any found since locked after), then the user's row, then the
    * groups. Sole ownership is checked again under those locks.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param id
    *   The identifier of the group.
    *
    * @return
    *   An effect producing each resource passed to the user, with everyone its
    *   grants reached before.
    */
  def delete(user: Long, id: Long): IO[List[(Resource, Set[Long])]] = db.run(
    (
      for
        principals <- principalsAt(user)
        guessed    <- doomedAt(principals, id)
        locked     <- lockResources(guessed, Set.empty)
        _          <- tables.lockUser(user)
        doomed     <- lockDoomed(principals, id, Set.empty)
        _          <- refuseUnless(
          doomed.nonEmpty,
          AuthRefusal.GroupMissing,
        )
        _      <- lockResources(doomed, locked)
        handed <- handOver(user, doomed)
        _      <- lift(doomed)
        _      <- deleteGroups(doomed)
      yield handed
    ).transactionally,
  )

  /**
    * The group and every group beneath it reached through groups the principals
    * own, or nothing if they do not own the group.
    */
  private def doomedAt
    (principals: Seq[Principal], root: Long)
    : DBIO[Seq[Long]] = grants
    .accessibleAt(
      principals,
      Resource.groupKind,
      Access.Own,
    )
    .flatMap: owned =>
      def descend(frontier: Set[Long], seen: Set[Long]): DBIO[Set[Long]] =
        ifAny(frontier.toSeq)(seen): parents =>
          tables
            .groups
            .filter(_.parentId inSet parents)
            .map(_.id)
            .result
            .flatMap(children =>
              val fresh = children.toSet.filter(owned) -- seen
              descend(fresh, seen ++ fresh),
            )
      if owned(root) then
        tables
          .groups
          .filter(_.id === root)
          .exists
          .result
          .flatMap(found =>
            if found then descend(Set(root), Set(root))
            else DBIO.successful(Set.empty),
          )
          .map(_.toSeq.sorted)
      else DBIO.successful(Seq.empty)

  /**
    * Locks the groups a deletion takes, reading them again under the locks
    * until no group has been nested beneath them since.
    */
  private def lockDoomed
    (
      principals: Seq[Principal],
      root: Long,
      locked: Set[Long],
    )
    : DBIO[Seq[Long]] = doomedAt(principals, root).flatMap: doomed =>
    val fresh = doomed.filterNot(locked)
    if fresh.isEmpty then DBIO.successful(doomed)
    else lock(fresh).flatMap(_ => lockDoomed(principals, root, locked ++ fresh))

  /**
    * Moves every group nested directly beneath a doomed group, but not doomed
    * itself, up to the nearest ancestor that survives.
    */
  private def lift(doomed: Seq[Long]): DBIO[Unit] = ifAny(doomed)(()): gone =>
    for
      rows      <- tables.groups.filter(_.id inSet gone).result
      survivors <- tables
        .locked(
          tables
            .groups
            .filter(group =>
              (group.parentId inSet gone) && !(group.id inSet gone),
            )
            .sortBy(_.id),
        )
        .result
      parents = rows.map(row => row.id -> row.parentId).toMap
      _ <- DBIO.sequence(survivors.map(row =>
        tables
          .groups
          .filter(_.id === row.id)
          .map(_.parentId)
          .update(GroupStore.surviving(row.parentId, parents)),
      ))
    yield ()

  /**
    * Invites a user to a group. Inviting a member or invitee changes nothing;
    * inviting an applicant admits them, and inviting oneself, or anyone whose
    * account one may change, joins. An unknown username is refused as such, so
    * callers can tell which names were mistyped.
    *
    * @param user
    *   The identifier of a user who may change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param username
    *   The invitee's username.
    *
    * @return
    *   An effect producing the invitee and the invitation sent, if any.
    */
  def invite(user: Long, group: Long, username: String): IO[Offer] =
    managing(user, group):
      for
        found <- tables
          .users
          .filter(_.usernameKey === Username.key(username))
          .result
          .headOption
        invitee <- required(
          found,
          AuthRefusal.UserMissing(username),
        )
        sent <- offer(user, group, invitee.id)
      yield Offer(invitee.toUser, sent)

  /**
    * Accepts an invitation, making its invitee a member of the group. Anyone
    * who may change the invitee's account ([[Access.Edit]] over
    * [[Resource.user]]) may accept for them; to anyone else, an invitation sent
    * to somebody else is missing.
    *
    * @param user
    *   The identifier of the invitee, or of someone who may change their
    *   account.
    *
    * @param invitation
    *   The identifier of the invitation.
    */
  def accept(user: Long, invitation: Long): IO[Unit] = db.run((for
    row <- answerableAt(user, invitation)
    _   <- lock(Seq(row.groupId))
    // Deleted under the lock, which both rechecks and claims the invitation.
    taken <- invitationOf(row.userId, invitation).delete
    _     <- refuseUnless(
      taken == 1,
      AuthRefusal.InvitationMissing,
    )
    _ <- enrol(row.groupId, row.userId)
  yield ()).transactionally)

  /**
    * Declines an invitation, deleting it. Whoever may accept it may decline it.
    * The deletion is a single statement, so a racing acceptance finds nothing.
    *
    * @param user
    *   The identifier of the invitee, or of someone who may change their
    *   account.
    *
    * @param invitation
    *   The identifier of the invitation.
    */
  def decline(user: Long, invitation: Long): IO[Unit] = db.run((for
    row     <- answerableAt(user, invitation)
    removed <- invitationOf(row.userId, invitation).delete
    _       <- refuseUnless(
      removed == 1,
      AuthRefusal.InvitationMissing,
    )
  yield ()).transactionally)

  /**
    * The invitation, if the user may answer it: as its invitee, or as someone
    * who may change the invitee's account. Missing otherwise.
    */
  private def answerableAt(user: Long, invitation: Long): DBIO[InvitationRow] =
    tables
      .invitations
      .filter(_.id === invitation)
      .result
      .headOption
      .flatMap(required(_, AuthRefusal.InvitationMissing))
      .flatMap(row =>
        consentsFor(user, row.userId).flatMap(may =>
          if may then DBIO.successful(row)
          else DBIO.failed(AuthProblem(AuthRefusal.InvitationMissing)),
        ),
      )

  /**
    * Whether a user may agree for another to join a group: as that person, or
    * as someone who may change their account.
    */
  private def consentsFor(user: Long, person: Long): DBIO[Boolean] =
    if user == person then DBIO.successful(true)
    else
      permissions
        .accessAt(user, Resource.user(person))
        .map(_.exists(_.includes(Access.Edit)))

  /**
    * Takes a user out of a group, doing nothing if they are not a member.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param group
    *   The identifier of the group.
    */
  def leave(user: Long, group: Long): IO[Unit] =
    holding(Seq(group))(_ => memberOf(group, user).delete.unit)

  /**
    * Makes a user who may change a group a member of it, doing nothing if they
    * are one.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param group
    *   The identifier of the group.
    */
  def join(user: Long, group: Long): IO[Unit] =
    managing(user, group)(enrol(group, user))

  /**
    * Asks for a user to join a group they can see, for its managers to admit or
    * decline. Asking twice or as a member changes nothing; asking when invited
    * accepts, and asking to join a group one may change joins.
    *
    * @param user
    *   The identifier of the user asking.
    *
    * @param group
    *   The identifier of the group.
    */
  def request(user: Long, group: Long): IO[Unit] = holding(Seq(group)): held =>
    for
      row <- required(
        held.headOption,
        AuthRefusal.GroupMissing,
      )
      level <- levelAt(user, group)
      ready <-
        if level.exists(_.includes(Access.Edit)) then DBIO.successful(true)
        else invitationTo(group, user).exists.result
      _ <- if ready then enrol(group, user) else ask(row, user)
    yield ()

  /**
    * Withdraws a user's request to join a group, doing nothing if there is
    * none.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param group
    *   The identifier of the group.
    */
  def withdraw(user: Long, group: Long): IO[Unit] =
    holding(Seq(group))(_ => requestFrom(group, user).delete.unit)

  /**
    * Admits an applicant to a group. Admitting a member changes nothing, and
    * anyone else who has not asked is refused.
    *
    * @param user
    *   The identifier of a user who may change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param applicant
    *   The identifier of the applicant.
    */
  def admit(user: Long, group: Long, applicant: Long): IO[Unit] =
    managing(user, group):
      for
        present <- memberOf(group, applicant).exists.result
        asked   <- requestFrom(group, applicant).exists.result
        _       <-
          if present then DBIO.unit
          else if asked then enrol(group, applicant)
          else DBIO.failed(AuthProblem(AuthRefusal.RequestMissing))
      yield ()

  /**
    * Makes a group public, so anyone can find it and ask to join, or private.
    * Pending requests stand either way.
    *
    * @param user
    *   The identifier of a user who may change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param public
    *   Whether the group is to be public.
    */
  def setPublic(user: Long, group: Long, public: Boolean): IO[Unit] =
    managing(user, group):
      tables.groups.filter(_.id === group).map(_.public).update(public).unit

  /**
    * Gives a group an invite link unless it has one.
    *
    * @param user
    *   The identifier of a user who may change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @return
    *   An effect producing the link's code.
    */
  def link(user: Long, group: Long): IO[String] =
    managing(user, group)(links.ensure(user, LinkTarget.Joining(group)))

  /**
    * Replaces a group's invite link with one of a new code.
    *
    * @param user
    *   The identifier of a user who may change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @return
    *   An effect producing the new code.
    */
  def relink(user: Long, group: Long): IO[String] =
    managing(user, group)(links.renew(user, LinkTarget.Joining(group)))

  /**
    * Turns off a group's invite link.
    *
    * @param user
    *   The identifier of a user who may change the group.
    *
    * @param group
    *   The identifier of the group.
    */
  def unlink(user: Long, group: Long): IO[Unit] =
    managing(user, group)(links.delete(LinkTarget.Joining(group)))

  /**
    * Enrols a user in the group a link leads to, provided it still does once
    * the group is locked. Composes into the caller's transaction.
    */
  private[server] def follow
    (user: Long, group: Long, code: String)
    : DBIO[Unit] =
    for
      _     <- lock(Seq(group))
      found <- links.find(code)
      _     <-
        if found.flatMap(_._1.target).contains(LinkTarget.Joining(group)) then
          enrol(group, user)
        else DBIO.failed(AuthProblem(AuthRefusal.LinkMissing))
    yield ()

  /** A group's name and whether the user is a member, or `None` if missing. */
  private[server] def glance
    (user: Long, group: Long)
    : DBIO[Option[(String, Boolean)]] = tables
    .groups
    .filter(_.id === group)
    .map(row => (row.name, memberOf(group, user).exists))
    .result
    .headOption

  /**
    * Who sees anything of some groups and what their members belong to through
    * them. Missing groups concern nobody, so ask before deleting.
    */
  private[server] def surroundings
    (groups: Seq[Long])
    : IO[GroupStore.Surroundings] = db.run:
    for
      rows <- rowsOf(groups.distinct)
      ids = rows.map(_.id)
      holding  <- grants.grantedAt(Resource.groupKind, ids, Access.View)
      managers <- usersOf(holding.map(_.principal).distinct)
      parents  <- ancestry(ids)
      enclosing = GroupStore.withAncestors(ids, parents)
      enrolled   <- pairsIn(memberPairs, enclosing)
      invitees   <- pairsIn(invitationPairs, ids)
      applicants <- pairsIn(requestPairs, ids)
      direct = ids.toSet
    yield GroupStore.Surroundings(
      managers,
      enrolled.collect { case (group, user) if direct(group) => user }.toSet,
      managers ++ (enrolled ++ invitees ++ applicants).map(_._2),
      rows.exists(_.public),
      enclosing.toSet,
    )

  /**
    * Whom a change to the grants over a resource concerns: for a group,
    * everyone who sees anything of it, as its owners are shown to its members,
    * and otherwise whoever the host decides.
    */
  private[server] def grantsChanged
    (resource: Resource, formerly: Set[Long])
    : IO[Affected] =
    if resource.kind == Resource.groupKind then
      surroundings(Seq(resource.id)).map(around =>
        Affected.Groups(
          Audience.People(around.people ++ formerly),
          around.enclosing,
        ),
      )
    else if resource.kind == Resource.userKind then
      db.run(permissions.holding(resource))
        .map(managers => Affected.Accounts(managers ++ formerly + resource.id))
    else IO.pure(Affected.Grants(resource, formerly))

  /**
    * The groups deleting this one would take or move, or nothing if it would
    * fail.
    */
  private[server] def subtreeOf(user: Long, group: Long): IO[Seq[Long]] = db
    .run:
      for
        principals <- principalsAt(user)
        doomed     <- doomedAt(principals, group)
        moved      <- ifAny(doomed)(Seq.empty[Long]): gone =>
          tables.groups.filter(_.parentId inSet gone).map(_.id).result
      yield (doomed ++ moved).distinct

  /**
    * The group an invitation is to and its invitee, if the user may answer it
    * (see [[accept]]), or `None`.
    */
  private[server] def answerable
    (user: Long, invitation: Long)
    : IO[Option[(Long, Long)]] = db
    .run(answerableAt(user, invitation).asTry)
    .map(_.toOption.map(row => (row.groupId, row.userId)))

  /**
    * Whom a change to how a user is shown, such as their name, concerns, as far
    * as groups show them: everyone who sees anything of the groups they are in,
    * invited to or asking to join.
    */
  private[server] def acquaintances(user: Long): IO[Affected] = db
    .run(
      for
        joined  <- directOf(user)
        invited <- groupsWith(invitationPairs, user)
        asked   <- groupsWith(requestPairs, user)
      yield (joined.toSet ++ invited ++ asked).toSeq,
    )
    .flatMap(surroundings)
    .map(around =>
      Affected.Groups(
        Audience.People(around.people + user),
        around.enclosing,
      ),
    )

  /**
    * Whom a change to who is in, invited to or asking to join some groups
    * concerns: the groups' managers and members, that person, and whoever
    * manages the person's account, who sees what awaits it.
    */
  private[server] def membersChanged
    (groups: Seq[Long], person: Long)
    : IO[Affected] =
    for
      around   <- surroundings(groups)
      managers <- db.run(permissions.holding(Resource.user(person)))
    yield around.membersChanged(person, managers)

  /**
    * Removes a member, invitee or applicant from a group.
    *
    * @param user
    *   The identifier of a user who may change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param person
    *   The identifier of the person to remove.
    */
  def remove(user: Long, group: Long, person: Long): IO[Unit] =
    managing(user, group):
      DBIO.seq(
        memberOf(group, person).delete,
        invitationTo(group, person).delete,
        requestFrom(group, person).delete,
      )

  /**
    * Locks, in ascending order, the resources the given groups own that are not
    * locked already.
    *
    * @return
    *   An action yielding every resource now locked.
    */
  private def lockResources
    (doomed: Seq[Long], locked: Set[Resource])
    : DBIO[Set[Resource]] = grants
    .ownedByAny(doomed.map(Principal.Group(_)))
    .flatMap(owned =>
      val others = owned.filterNot(GroupStore.among(doomed))
      DBIO
        .sequence(others.filterNot(locked).map(named))
        .map(_ => locked ++ others),
    )

  /**
    * Grants the user `Own` over every resource still standing that the given
    * groups, which the caller holds locked with the resources, alone own.
    */
  private def handOver
    (user: Long, doomed: Seq[Long])
    : DBIO[List[(Resource, Set[Long])]] = grants
    .ownedSolelyBy(doomed.map(Principal.Group(_)))
    .flatMap(orphaned =>
      DBIO
        .sequence(
          orphaned
            .filterNot(GroupStore.among(doomed))
            .toList
            .map(resource =>
              named(resource).flatMap(
                _.fold(DBIO.successful(Option.empty[(Resource, Set[Long])]))(
                  _ =>
                    for
                      formerly <- permissions.holding(resource)
                      // Nobody holds a grant over their own account, which is
                      // theirs alone again.
                      _ <-
                        if Permissions.ownAccount(user, resource) then
                          release(user)
                        else
                          grants.grant(Grant(
                            resource,
                            Principal.Person(user),
                            Access.Own,
                          ))
                    yield Some(resource -> formerly),
                ),
              ),
            ),
        )
        .map(_.flatten),
    )

  /**
    * Makes an account its user's alone again: every grant over it withdrawn,
    * and any suspension lifted. Callers must hold the user's row locked.
    */
  private[server] def release(user: Long): DBIO[Unit] = DBIO.seq(
    grants.revokeOver(Resource.user(user)),
    tables.suspensions.filter(_.userId === user).delete,
  )

  /**
    * Deletes groups the caller already holds locked, and all that goes with
    * them, having lifted any survivor nested beneath them.
    */
  private def deleteGroups(doomed: Seq[Long]): DBIO[Unit] =
    ifAny(doomed)(()): gone =>
      val principals = gone.map(Principal.Group(_))
      for
        _ <- tables.members.filter(_.groupId inSet gone).delete
        _ <- tables.invitations.filter(_.groupId inSet gone).delete
        _ <- tables.requests.filter(_.groupId inSet gone).delete
        _ <- links.deleteToGroups(gone)
        _ <- grants.revokeHeldBy(principals)
        _ <-
          DBIO.sequence(gone.map(id => grants.revokeOver(Resource.group(id))))
        _ <- cascade(principals)
        _ <- tables.groups.filter(_.id inSet gone).delete
      yield ()

  /**
    * Finds the groups a user's account takes with it: those they alone own, and
    * those that only groups taken with it own, and so on. In the caller's
    * transaction, which should hold the user's row locked.
    */
  private[server] def soleGroupsOf(user: Long): DBIO[Seq[Long]] =
    def grow(doomed: Set[Long]): DBIO[Set[Long]] = grants
      .ownedSolelyBy(
        Principal.Person(user) +: doomed.toSeq.map(Principal.Group(_)),
      )
      .flatMap: owned =>
        val found = owned.filter(_.kind == Resource.groupKind).map(_.id).toSet
        if found.subsetOf(doomed) then DBIO.successful(doomed)
        else grow(doomed ++ found)
    grow(Set.empty).map(_.toSeq.sorted)

  /**
    * Removes a user from the groups for their account's deletion: the groups it
    * takes with it ([[soleGroupsOf]]), their memberships, invitations and
    * requests, and every invite link they made.
    */
  private[server] def forget(user: Long, doomed: Seq[Long]): DBIO[Unit] =
    for
      _ <- lock(doomed)
      _ <- lift(doomed)
      _ <- deleteGroups(doomed)
      _ <- tables.members.filter(_.userId === user).delete
      _ <- tables.invitations.filter(_.userId === user).delete
      _ <- tables.requests.filter(_.userId === user).delete
      _ <- links.deleteMadeBy(user)
    yield ()

  /**
    * Lists the principals a user may address: themselves, the system if they
    * act for it, the groups they may change, and those groups' accepted
    * members. Invitees are excluded, as addressing someone gives them
    * something, which needs a relation they agreed to.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the principals, the user first.
    */
  def addressable(user: Long): IO[List[Principal]] = db.run:
    for
      principals <- principalsAt(user)
      groups     <- grants.accessibleAt(
        principals,
        Resource.groupKind,
        Access.Edit,
      )
      existing <- tables
        .groups
        .filter(_.id inSet groups)
        .sortBy(_.id)
        .map(_.id)
        .result
      members <- tables
        .members
        .filter(_.groupId inSet existing)
        .map(_.userId)
        .distinct
        .sortBy(identity)
        .result
    yield (Principal.Person(user) ::
      principals.filter(_ == Principal.System)) ++
      existing.map(Principal.Group(_)) ++
      members.filterNot(_ == user).map(Principal.Person(_))

  /**
    * Checks whether [[addressable]] would list a principal.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param principal
    *   The principal to address.
    *
    * @return
    *   An effect producing whether the user may address the principal.
    */
  def mayAddress(user: Long, principal: Principal): IO[Boolean] =
    db.run(mayAddressAt(user, principal))

  /** As [[mayAddress]], in the caller's transaction. */
  private[server] def mayAddressAt
    (user: Long, principal: Principal)
    : DBIO[Boolean] = principal match
    case Principal.Person(id) if id == user => DBIO.successful(true)
    case Principal.Person(id)               =>
      for
        principals <- principalsAt(user)
        groups     <- grants.accessibleAt(
          principals,
          Resource.groupKind,
          Access.Edit,
        )
        found <- tables
          .members
          .filter(row => row.userId === id && (row.groupId inSet groups))
          .exists
          .result
      yield found
    case Principal.Group(id) => tables
        .groups
        .filter(_.id === id)
        .exists
        .result
        .flatMap(found =>
          if found then levelAt(user, id).map(_.exists(_.includes(Access.Edit)))
          else DBIO.successful(false),
        )
    case Principal.System => actsForSystem(user)

  /**
    * Enrols people in a group the user may change, in the caller's transaction,
    * locking the group first; a group they may not change is missing.
    */
  private[server] def enrolAll
    (user: Long, group: Long, people: Seq[Long])
    : DBIO[Unit] =
    for
      held <- lock(Seq(group))
      _    <- ensureLevel(held, user, group, Access.Edit)
      _    <- DBIO.seq(people.map(enrol(group, _))*)
    yield ()

  private def membershipsOf(user: Long) = tables
    .members
    .filter(_.userId === user)
    .join(tables.groups)
    .on(_.groupId === _.id)

  private def memberOf(group: Long, user: Long) = tables
    .members
    .filter(member => member.groupId === group && member.userId === user)

  private def invitationTo(group: Long, user: Long) = tables
    .invitations
    .filter(invite => invite.groupId === group && invite.userId === user)

  private def requestFrom(group: Long, user: Long) = tables
    .requests
    .filter(asking => asking.groupId === group && asking.userId === user)

  private def invitationOf(user: Long, id: Long) = tables
    .invitations
    .filter(invite => invite.id === id && invite.userId === user)

  /** Locks groups' rows in ascending order (see [[GroupStore]]). */
  private def lock(groups: Seq[Long]): DBIO[Seq[GroupRow]] =
    ifAny(groups)(Seq.empty): held =>
      tables.locked(tables.groups.filter(_.id inSet held).sortBy(_.id)).result

  /** Hands the change the rows read under the groups' locks. */
  private def holding[X]
    (groups: Seq[Long])
    (change: Seq[GroupRow] => DBIO[X])
    : IO[X] = db.run(lock(groups).flatMap(change).transactionally)

  /** The group is missing to anyone who may not change it. */
  private def managing[X](user: Long, group: Long)(change: DBIO[X]): IO[X] =
    holding(
      Seq(group),
    )(held => ensureLevel(held, user, group, Access.Edit).flatMap(_ => change))

  /** The user's access to a group, in the caller's transaction. */
  private def levelAt(user: Long, group: Long): DBIO[Option[Access]] =
    principalsAt(user).flatMap(grants.accessOf(_, Resource.group(group)))

  private def ensureLevel
    (
      held: Seq[GroupRow],
      user: Long,
      group: Long,
      least: Access,
    )
    : DBIO[Unit] =
    if held.exists(_.id == group) then
      levelAt(user, group).flatMap(level =>
        refuseUnless(
          level.exists(_.includes(least)),
          AuthRefusal.GroupMissing,
        ),
      )
    else DBIO.failed(AuthProblem(AuthRefusal.GroupMissing))

  private def ensureParent
    (
      held: Seq[GroupRow],
      user: Long,
      parent: Option[Long],
    )
    : DBIO[Unit] = parent.fold(DBIO.unit)(id =>
    if held.exists(_.id == id) then
      levelAt(user, id).flatMap(level =>
        refuseUnless(
          level.exists(_.includes(Access.Edit)),
          AuthRefusal.ParentGroupMissing,
        ),
      )
    else DBIO.failed(AuthProblem(AuthRefusal.ParentGroupMissing)),
  )

  private def ensureAcyclic(id: Long, parent: Option[Long]): DBIO[Unit] = parent
    .fold[DBIO[Unit]](DBIO.unit)(target =>
      descendants(Seq(id)).flatMap(subtree =>
        refuseUnless(
          !subtree(target),
          AuthRefusal.GroupInsideItself,
        ),
      ),
    )

  /**
    * Invites a user unless they are a member or invitee, or enrols them if the
    * inviter may agree for them (as themselves, or as someone who may change
    * their account) or they asked to join, yielding the invitation sent, if
    * any. Callers must hold the group's lock.
    */
  private def offer
    (inviter: Long, group: Long, user: Long)
    : DBIO[Option[Long]] = consentsFor(inviter, user).flatMap(agreed =>
    if agreed then enrol(group, user).map(_ => None)
    else
      memberOf(group, user)
        .exists
        .result
        .zip(invitationTo(group, user).exists.result)
        .zip(requestFrom(group, user).exists.result)
        .flatMap:
          case ((false, false), false) =>
            (tables.invitations.returning(tables.invitations.map(_.id)) +=
              InvitationRow(0, group, user, inviter)).map(Some(_))
          case ((false, _), true) => enrol(group, user).map(_ => None)
          case _                  => DBIO.successful(None),
  )

  /**
    * Records a request to join a group the user can see, unless they are a
    * member or asked already. Callers must hold the group's lock.
    */
  private def ask(group: GroupRow, user: Long): DBIO[Unit] = maySee(user, group)
    .flatMap(refuseUnless(_, AuthRefusal.GroupMissing))
    .flatMap(_ =>
      insertUnless(
        memberOf(group.id, user).exists || requestFrom(group.id, user).exists,
      )(tables.requests += RequestRow(group.id, user)),
    )

  /**
    * Whether a group is public, or the user is a member of it or of a group it
    * is nested inside.
    */
  private def maySee(user: Long, group: GroupRow): DBIO[Boolean] =
    if group.public then DBIO.successful(true)
    else
      for
        direct  <- directOf(user)
        parents <- ancestry(Seq(group.id))
      yield GroupStore
        .withAncestors(Seq(group.id), parents)
        .exists(direct.toSet)

  /**
    * Enrols a user, settling any invitation or request that brought them.
    * Callers must hold the group's lock.
    */
  private def enrol(group: Long, user: Long): DBIO[Unit] =
    for
      _ <- invitationTo(group, user).delete
      _ <- requestFrom(group, user).delete
      _ <- addMember(group, user)
    yield ()

  private def addMember(group: Long, user: Long): DBIO[Unit] = insertUnless(
    memberOf(group, user).exists,
  )(tables.members += MemberRow(group, user))

  /** Callers must hold the group's lock, or the check may not hold. */
  private def insertUnless(found: Rep[Boolean])(insert: DBIO[Any]): DBIO[Unit] =
    found.result.flatMap(present => if present then DBIO.unit else insert.unit)

object GroupStore:

  /** The default lookup of resources, locking nothing and finding each. */
  val everywhere: Resource => DBIO[Option[String]] =
    resource => DBIO.successful(Some(resource.kind))

  /** Whether a resource is one of the given groups. */
  private def among(groups: Seq[Long])(resource: Resource): Boolean =
    resource.kind == Resource.groupKind && groups.contains(resource.id)

  /** The nearest ancestor of a parent that is not one of the doomed groups. */
  private def surviving
    (
      parent: Option[Long],
      doomed: Map[Long, Option[Long]],
    )
    : Option[Long] =
    def climb(at: Option[Long], seen: Set[Long]): Option[Long] = at match
      case Some(id) if doomed.contains(id) && !seen(id) =>
        climb(doomed(id), seen + id)
      case Some(id) if doomed.contains(id) => None
      case other                           => other
    climb(parent, Set.empty)

  /** The system first, then people, then groups, each by name. */
  private def holderOrder(holder: Holder): (Int, String) =
    val rank = holder.principal match
      case Principal.System    => 0
      case Principal.Person(_) => 1
      case Principal.Group(_)  => 2
    (rank, holder.name.getOrElse(""))

  /**
    * Who sees anything of some groups, and what their members belong to through
    * them.
    *
    * @param managers
    *   Everyone holding at least `View` over the groups.
    *
    * @param members
    *   The direct members of the groups.
    *
    * @param people
    *   Everyone who sees anything of the groups: managers, direct members,
    *   invitees, applicants, and the members of every enclosing group.
    *
    * @param public
    *   Whether any of the groups is public.
    *
    * @param enclosing
    *   The groups, with every group enclosing them.
    */
  private[server] final case class Surroundings
    (
      managers: Set[Long],
      members: Set[Long],
      people: Set[Long],
      public: Boolean,
      enclosing: Set[Long],
    ):

    /**
      * Whom a change to the groups' members concerns, given whoever manages the
      * person's account.
      */
    def membersChanged(person: Long, accountManagers: Set[Long]): Affected =
      Affected.Groups(
        Audience.People(managers ++ members ++ accountManagers + person),
        enclosing,
      )

  private def byGroup(pairs: Seq[(Long, UserRow)]): Map[Long, List[User]] =
    pairs
      .groupMap(_._1)(_._2.toUser)
      .view
      .mapValues(_.sortBy(_.username).toList)
      .toMap

  /** The groups enclosing one, nearest first, never climbing to one twice. */
  private def above
    (
      id: Long,
      parents: Map[Long, Option[Long]],
    )
    : List[Long] =
    def climb(at: Long, seen: List[Long]): List[Long] =
      parents.get(at).flatten match
        case Some(parent) if parent != id && !seen.contains(parent) =>
          climb(parent, seen :+ parent)
        case _ => seen
    climb(id, List.empty)

  /** The groups with every ancestor, never climbing into a group twice. */
  private def withAncestors
    (
      direct: Seq[Long],
      parents: Map[Long, Option[Long]],
    )
    : List[Long] =
    def climb(id: Long, seen: Set[Long]): Set[Long] =
      if seen(id) then seen
      else parents.get(id).flatten.fold(seen + id)(climb(_, seen + id))
    direct
      .foldLeft(Set.empty[Long])((seen, id) => climb(id, seen))
      .toList
      .sorted
