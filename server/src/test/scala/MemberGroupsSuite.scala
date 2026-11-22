package com.alecdorrington.hecate
package server

import Fixtures.{enrol, refusal, register}
import cats.effect.IO
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  Access, GroupDetails, GroupInvitation, InviteStatus, Principal, Resource, User,
}
import munit.CatsEffectSuite

/** Tests of groups that are members of other groups. */
class MemberGroupsSuite extends CatsEffectSuite:

  private val english = Wording.english

  private def withStores
    (name: String)
    (check: (GroupStore, UserStore, TestDb) => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"member-groups-$name")
    .use(db =>
      check(
        GroupStore(TestDb.tables, db),
        UserStore(TestDb.tables, db),
        db,
      ),
    )

  /** The invitation for one group to join another, as an answerer sees it. */
  private def invitationFor
    (
      groups: GroupStore,
      answerer: User,
      invitee: Long,
    )
    : IO[GroupInvitation] = groups
    .groupInvitations(answerer.id)
    .map(_.find(_.invitee.id == invitee).get)

  test("a group joins another once its managers accept, and its members too"):
    withStores("accept"): (groups, users, _) =>
      for
        lead     <- register(users, "lead")
        captain  <- register(users, "captain")
        player   <- register(users, "player")
        stranger <- register(users, "stranger")
        research <- groups.create(lead.id, GroupDetails("Research"))
        crew     <- groups.create(captain.id, GroupDetails("Crew"))
        squad    <- groups.create(
          captain.id,
          GroupDetails("Squad", Some(crew.id)),
        )
        _ <- enrol(groups, captain, squad.id, player)
        // The lead may name Crew only once they can see it, here as a member.
        hidden   <- refusal(groups.inviteGroup(lead.id, research.id, crew.id))
        _        <- enrol(groups, captain, crew.id, lead)
        offer    <- groups.inviteGroup(lead.id, research.id, crew.id)
        before   <- groups.enclosing(player.id)
        theirs   <- groups.groupInvitations(captain.id)
        others   <- groups.groupInvitations(stranger.id)
        sent     <- invitationFor(groups, captain, crew.id)
        stolen   <- refusal(groups.acceptGroup(stranger.id, sent.id))
        _        <- groups.acceptGroup(captain.id, sent.id)
        after    <- groups.enclosing(player.id)
        within   <- groups.membersWithin(Seq(research.id))
        leads    <- groups.managed(lead.id)
        captains <- groups.managed(captain.id)
        left     <- groups.groupInvitations(captain.id)
      yield
        assertEquals(hidden, Some(english.groupMissing))
        assertEquals(offer.status, InviteStatus.Invited)
        assert(offer.invitation.isDefined)
        assertEquals(before.toSet, Set(crew.id, squad.id))
        assertEquals(
          theirs.map(_.group.id),
          List(research.id),
        )
        assertEquals(others, Nil)
        assertEquals(
          stolen,
          Some(english.invitationMissing),
        )
        assertEquals(
          after.toSet,
          Set(crew.id, squad.id, research.id),
        )
        assert(
          within.contains(player.id),
          "a nested member was not reached",
        )
        assertEquals(
          leads.find(_.group.id == research.id).map(_.memberGroups.map(_.id)),
          Some(List(crew.id)),
        )
        assertEquals(
          captains.find(_.group.id == crew.id).map(_.memberOf.map(_.id)),
          Some(List(research.id)),
        )
        assertEquals(left, Nil)

  test("inviting a group one may change joins it at once, and only once"):
    withStores("join"): (groups, users, _) =>
      for
        owner  <- register(users, "owner")
        member <- register(users, "member")
        club   <- groups.create(owner.id, GroupDetails("Club"))
        choir  <- groups.create(owner.id, GroupDetails("Choir"))
        _      <- enrol(groups, owner, choir.id, member)
        first  <- groups.inviteGroup(owner.id, club.id, choir.id)
        again  <- groups.inviteGroup(owner.id, club.id, choir.id)
        joined <- groups.enclosing(member.id)
        shown  <- groups.memberships(member.id)
      yield
        assertEquals(first.status, InviteStatus.Joined)
        assertEquals(first.invitation, None)
        assertEquals(again.status, InviteStatus.Member)
        assertEquals(joined.toSet, Set(club.id, choir.id))
        assertEquals(
          shown.find(_.group.id == choir.id).map(_.memberOf.map(_.name)),
          Some(List("Club")),
        )

  test("no group is ever within itself, as a member or nested"):
    withStores("cycles"): (groups, users, _) =>
      for
        owner <- register(users, "owner")
        outer <- groups.create(owner.id, GroupDetails("Outer"))
        inner <- groups.create(
          owner.id,
          GroupDetails("Inner", Some(outer.id)),
        )
        loose  <- groups.create(owner.id, GroupDetails("Loose"))
        itself <- refusal(groups.inviteGroup(owner.id, outer.id, outer.id))
        nested <- refusal(groups.inviteGroup(owner.id, inner.id, outer.id))
        _      <- groups.inviteGroup(owner.id, loose.id, outer.id)
        round  <- refusal(groups.inviteGroup(owner.id, inner.id, loose.id))
        moved  <- refusal(groups.update(
          owner.id,
          loose.id,
          GroupDetails("Loose", Some(inner.id)),
        ))
      yield List(itself, nested, round, moved).foreach(refused =>
        assertEquals(
          refused,
          Some(english.groupInsideItself),
        ),
      )

  test("either group's managers may end a membership; deletion ends it too"):
    withStores("remove"): (groups, users, _) =>
      for
        lead     <- register(users, "lead")
        captain  <- register(users, "captain")
        stranger <- register(users, "stranger")
        research <- groups.create(lead.id, GroupDetails("Research"))
        design   <- groups.create(lead.id, GroupDetails("Design"))
        crew     <- groups.create(captain.id, GroupDetails("Crew"))
        _        <- enrol(groups, captain, crew.id, lead)
        _        <- groups.inviteGroup(lead.id, research.id, crew.id)
        _        <- groups.inviteGroup(lead.id, design.id, crew.id)
        sent     <- invitationFor(groups, captain, crew.id)
        _        <- groups.acceptGroup(captain.id, sent.id)
        refused  <-
          refusal(groups.removeGroup(stranger.id, research.id, crew.id))
        // The captain takes Crew out of Research, and the lead withdraws Design's.
        _       <- groups.removeGroup(captain.id, sent.group.id, crew.id)
        _       <- groups.removeGroup(lead.id, design.id, crew.id)
        emptied <- groups.managed(lead.id)
        pending <- groups.groupInvitations(captain.id)
        _       <- groups.inviteGroup(lead.id, research.id, crew.id)
        _       <- groups.delete(lead.id, research.id)
        gone    <- groups.groupInvitations(captain.id)
      yield
        assertEquals(refused, Some(english.groupMissing))
        assertEquals(
          emptied.flatMap(group => group.memberGroups ++ group.invitedGroups),
          Nil,
        )
        assertEquals(pending, Nil)
        assertEquals(gone, Nil)

  test("a grant to a group reaches the members of its member groups"):
    withStores("grants"): (groups, users, db) =>
      for
        owner  <- register(users, "owner")
        member <- register(users, "member")
        club   <- groups.create(owner.id, GroupDetails("Club"))
        choir  <- groups.create(owner.id, GroupDetails("Choir"))
        _      <- enrol(groups, owner, choir.id, member)
        _      <- groups.inviteGroup(owner.id, club.id, choir.id)
        grants = GrantStore(TestDb.tables, db)
        _ <- Fixtures.grant(grants, db)(
          Resource("book", 1),
          Principal.Group(club.id),
          Access.View,
        )
        holders <- Permissions(groups, grants).holders(Resource("book", 1))
      yield assert(
        holders.contains(member.id),
        "the grant missed a member",
      )
