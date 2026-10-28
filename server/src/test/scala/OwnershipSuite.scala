package com.alecdorrington.hecate
package server

import Fixtures.{enrol, grant, invitationTo, refusal, register}
import TestDb.tables.profile.api.*
import cats.effect.IO
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  Access, AuthRefusal, GroupDetails, Holder, Principal, Resource, User,
}
import munit.CatsEffectSuite

/** Who manages a group, by grants over it, and who acts for the system. */
class OwnershipSuite extends CatsEffectSuite:

  import OwnershipSuite.*

  test("a group's creator owns it, and nobody else manages it or nests in it"):
    withStores("creator"): world =>
      import world.*
      for
        owner    <- register(users, "owner")
        stranger <- register(users, "stranger")
        team     <- groups.create(owner.id, GroupDetails("Team"))
        mine     <- groups.managed(owner.id)
        theirs   <- groups.managed(stranger.id)
        refused  <- refusal(groups.invite(stranger.id, team.id, "owner"))
        absent   <- refusal(groups.invite(stranger.id, 9999L, "owner"))
        deleted  <- refusal(groups.delete(stranger.id, team.id))
        nested   <- refusal(groups.create(
          stranger.id,
          GroupDetails("Inside", Some(team.id)),
        ))
        kept <- groups.managed(owner.id)
      yield
        assertEquals(
          mine.map(view => (view.group.id, view.access)),
          List(team.id -> Access.Own),
        )
        assertEquals(theirs, List.empty)
        // Another's group reads exactly like one that does not exist.
        assertEquals(
          refused,
          said(AuthRefusal.GroupMissing),
        )
        assertEquals(refused, absent)
        assertEquals(
          deleted,
          said(AuthRefusal.GroupMissing),
        )
        // Nesting needs the right to change the parent.
        assertEquals(
          nested,
          said(AuthRefusal.ParentGroupMissing),
        )
        assertEquals(kept.map(_.group.id), List(team.id))

  test("a viewer sees who is in a group but changes nothing"):
    withStores("viewer"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        viewer <- register(users, "viewer")
        member <- register(users, "member")
        team   <- groups.create(owner.id, GroupDetails("Team"))
        _      <- enrol(groups, owner, team.id, member)
        _      <- groups.link(owner.id, team.id)
        _      <- give(
          Resource.group(team.id),
          Principal.Person(viewer.id),
          Access.View,
        )
        seen    <- groups.managed(viewer.id)
        invited <- refusal(groups.invite(viewer.id, team.id, "owner"))
        renamed <- refusal(groups.update(
          viewer.id,
          team.id,
          GroupDetails("Mine now"),
        ))
        deleted <- refusal(groups.delete(viewer.id, team.id))
      yield
        assertEquals(seen.map(_.access), List(Access.View))
        assertEquals(seen.flatMap(_.members), List(member))
        // A viewer may not hand out the link, which would let people in.
        assertEquals(seen.map(_.inviteCode), List(None))
        assertEquals(
          invited,
          said(AuthRefusal.GroupMissing),
        )
        assertEquals(
          renamed,
          said(AuthRefusal.GroupMissing),
        )
        assertEquals(
          deleted,
          said(AuthRefusal.GroupMissing),
        )

  test("an editor changes who is in a group, but cannot delete it"):
    withStores("editor"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        editor <- register(users, "editor")
        member <- register(users, "member")
        team   <- groups.create(owner.id, GroupDetails("Team"))
        _      <- give(
          Resource.group(team.id),
          Principal.Person(editor.id),
          Access.Edit,
        )
        _       <- groups.invite(editor.id, team.id, member.username)
        invited <- invitationTo(groups, member, team.id)
        _       <- groups.accept(member.id, invited.id)
        _       <- groups.update(
          editor.id,
          team.id,
          GroupDetails("Renamed"),
        )
        _       <- groups.join(editor.id, team.id)
        seen    <- groups.managed(owner.id)
        sent    <- groups.invitations(member.id)
        deleted <- refusal(groups.delete(editor.id, team.id))
        mayAsk  <- groups.mayAddress(editor.id, Principal.Person(member.id))
      yield
        // An invitation says who sent it.
        assertEquals(invited.inviter, Some(editor))
        assertEquals(
          seen.map(_.group.name),
          List("Renamed"),
        )
        assertEquals(
          seen.flatMap(_.members).map(_.id).sorted,
          List(editor.id, member.id).sorted,
        )
        assertEquals(sent, List.empty)
        assertEquals(
          deleted,
          said(AuthRefusal.GroupMissing),
        )
        assert(mayAsk)

  test("members see every owner of their group"):
    withStores("owners"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        other  <- register(users, "other")
        member <- register(users, "member")
        team   <- groups.create(owner.id, GroupDetails("Team"))
        _      <- give(
          Resource.group(team.id),
          Principal.Person(other.id),
          Access.Own,
        )
        _ <- give(
          Resource.group(team.id),
          Principal.System,
          Access.Own,
        )
        _      <- enrol(groups, owner, team.id, member)
        joined <- groups.memberships(member.id)
      yield assertEquals(
        joined.map(_.owners),
        List(List(
          Holder(Principal.System, None),
          Holder(
            Principal.Person(other.id),
            Some("other"),
          ),
          Holder(
            Principal.Person(owner.id),
            Some("owner"),
          ),
        )),
      )

  test("a group owned by another is managed by that group's members"):
    withStores("owning-group"): world =>
      import world.*
      for
        head      <- register(users, "head")
        colleague <- register(users, "colleague")
        staff     <- groups.create(head.id, GroupDetails("Staff"))
        _         <- enrol(groups, head, staff.id, colleague)
        rota      <- groups.create(head.id, GroupDetails("Rota"))
        _         <- give(
          Resource.group(rota.id),
          Principal.Group(staff.id),
          Access.Own,
        )
        seen <- groups.managed(colleague.id)
        _    <- groups.update(
          colleague.id,
          rota.id,
          GroupDetails("Weekly rota"),
        )
      yield assertEquals(
        seen.map(view => (view.group.id, view.access)),
        List(rota.id -> Access.Own),
      )

  test("an account takes the groups it and its groups alone own, no others"):
    withStores("account", Set("ops@example.com")): world =>
      import world.*
      for
        first  <- register(users, "first")
        second <- register(users, "second")
        _      <- confirm(first, "ops@example.com")
        shared <- groups.create(first.id, GroupDetails("Shared"))
        alone  <- groups.create(first.id, GroupDetails("Alone"))
        board  <- groups.create(first.id, GroupDetails("Board"))
        panel  <- groups.create(first.id, GroupDetails("Panel"))
        rota   <- groups.create(first.id, GroupDetails("Rota"))
        _      <- give(
          Resource.group(shared.id),
          Principal.Person(second.id),
          Access.Own,
        )
        // Owned only by a group the account alone owns.
        _ <- give(
          Resource.group(panel.id),
          Principal.Group(board.id),
          Access.Own,
        )
        _ <- db.run(grantsOf(panel.id, Principal.Person(first.id)).delete)
        // Owned by the system, for which the account acts as an operator.
        _ <- give(
          Resource.group(rota.id),
          Principal.System,
          Access.Own,
        )
        _        <- db.run(grantsOf(rota.id, Principal.Person(first.id)).delete)
        managing <- groups.managed(first.id)
        _        <- accounts.delete(first.id)
        left     <- groups.managed(second.id)
        gone     <- groups.names(Seq(alone.id, board.id, panel.id))
        kept     <- groups.names(Seq(rota.id))
        held     <- grants.over(Resource.group(shared.id))
      yield
        assertEquals(
          managing.find(_.group.id == rota.id).map(_.access),
          Some(Access.Own),
        )
        assertEquals(left.map(_.group.id), List(shared.id))
        assertEquals(gone, Map.empty)
        assertEquals(kept, Map(rota.id -> "Rota"))
        assertEquals(
          held,
          List(model.Grant(
            Resource.group(shared.id),
            Principal.Person(second.id),
            Access.Own,
          )),
        )

  test("deleting a group lifts a nested group someone else owns"):
    withStores("lift"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        other  <- register(users, "other")
        top    <- groups.create(owner.id, GroupDetails("Top"))
        middle <- groups.create(
          owner.id,
          GroupDetails("Middle", Some(top.id)),
        )
        _ <- give(
          Resource.group(middle.id),
          Principal.Person(other.id),
          Access.Edit,
        )
        // Someone who may change the middle group nests their own inside it.
        theirs <- groups.create(
          other.id,
          GroupDetails("Theirs", Some(middle.id)),
        )
        ours <- groups.create(
          owner.id,
          GroupDetails("Ours", Some(middle.id)),
        )
        _    <- groups.delete(owner.id, middle.id)
        left <- groups.managed(other.id)
        gone <- groups.names(Seq(middle.id, ours.id))
      yield
        assertEquals(gone, Map.empty)
        assertEquals(
          left.map(view => (view.group.id, view.group.parentId)),
          List(theirs.id -> Some(top.id)),
        )

  test(
    "whoever manages an enclosing group may address a nested group's members",
  ):
    withStores("addressers"): world =>
      import world.*
      for
        head   <- register(users, "head")
        leader <- register(users, "leader")
        member <- register(users, "member")
        all    <- groups.create(head.id, GroupDetails("Everyone"))
        _      <- give(
          Resource.group(all.id),
          Principal.Person(leader.id),
          Access.Edit,
        )
        squad <- groups.create(
          leader.id,
          GroupDetails("Squad", Some(all.id)),
        )
        _      <- enrol(groups, leader, squad.id, member)
        found  <- groups.addressersOf(member.id)
        nobody <- groups.addressersOf(head.id)
        within <- groups.membersWithin(Seq(all.id))
        none   <- groups.membersWithin(Seq.empty)
      yield
        assertEquals(found, Set(head.id, leader.id))
        assertEquals(nobody, Set.empty)
        assertEquals(within, List(member.id))
        assertEquals(none, List.empty)

  test("only a confirmed operator address acts for, and holds as, the system"):
    withStores(
      "operators",
      Set("Ops@Example.com", "two@example.com"),
    ): world =>
      import world.*
      val document = Resource("document", 1)
      for
        operator <- register(users, "operator")
        two      <- register(users, "two")
        pending  <- register(users, "pending")
        _        <- confirm(operator, "ops@example.com")
        _        <- confirm(two, "two@example.com")
        _        <- give(document, Principal.System, Access.Own)
        acting   <- groups.principalsOf(operator.id)
        others   <- groups.principalsOf(pending.id)
        mayGive  <- groups.mayAddress(operator.id, Principal.System)
        mayNot   <- groups.mayAddress(pending.id, Principal.System)
        listed   <- groups.addressable(operator.id)
        held     <- permissions.access(operator.id, document)
        theirs   <- permissions.access(pending.id, document)
        holders  <- permissions.holders(document)
      yield
        assertEquals(
          acting,
          List(
            Principal.Person(operator.id),
            Principal.System,
          ),
        )
        assertEquals(
          others,
          List(Principal.Person(pending.id)),
        )
        assert(mayGive)
        assert(!mayNot)
        assertEquals(
          listed.take(2),
          List(
            Principal.Person(operator.id),
            Principal.System,
          ),
        )
        // Whatever the system holds, every operator holds.
        assertEquals(held, Some(Access.Own))
        assertEquals(theirs, None)
        assertEquals(holders, Set(operator.id, two.id))

object OwnershipSuite:

  /** A refusal as the store reports it, in English. */
  private def said(refusal: AuthRefusal): Option[String] =
    Some(Wording.english.phrase(refusal))

  /** The stores of one throwaway database. */
  private final class World(val db: TestDb, operators: Set[String]):

    val users: UserStore   = UserStore(TestDb.tables, db)
    val grants: GrantStore = GrantStore(TestDb.tables, db)

    val groups: GroupStore =
      GroupStore(TestDb.tables, db, operators = operators)
    val permissions: Permissions = Permissions(groups, grants)

    val accounts: AccountStore = AccountStore(
      TestDb.tables,
      db,
      users,
      groups,
      grants,
      _ => DBIO.unit,
    )

    def give
      (
        resource: Resource,
        principal: Principal,
        access: Access,
      )
      : IO[Unit] = grant(grants, db)(resource, principal, access)

    /** Marks an address as confirmed, as opening its mailed link would. */
    def confirm(user: User, address: String): IO[Unit] = db
      .run(
        TestDb
          .tables
          .users
          .filter(_.id === user.id)
          .map(_.email)
          .update(Some(address)),
      )
      .void

    def grantsOf(group: Long, principal: Principal) = TestDb
      .tables
      .grants
      .filter(row =>
        row.resourceKind === Resource.groupKind && row.resourceId === group &&
        row.principalKind === principal.kind && row.principalId === principal.id,
      )

  private def withStores
    (
      name: String,
      operators: Set[String] = Set.empty,
    )
    (check: World => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"ownership-$name")
    .use(db => check(World(db, operators)))
