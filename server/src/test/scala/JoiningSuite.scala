package com.alecdorrington.hecate
package server

import Fixtures.{enrol, refusal, register}
import cats.effect.IO
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  GroupDetails, Invitation, InviteCode, LinkTarget, Principal, User,
}
import munit.CatsEffectSuite

class JoiningSuite extends CatsEffectSuite:

  private def withStores
    (name: String)
    (check: (GroupStore, UserStore, TestDb) => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"joining-$name")
    .use(db =>
      check(
        GroupStore(TestDb.tables, db),
        UserStore(TestDb.tables, db),
        db,
      ),
    )

  private def membersOf
    (
      groups: GroupStore,
      owner: User,
      group: Long,
    )
    : IO[List[Long]] = groups
    .managed(owner.id)
    .map(_.find(_.group.id == group).toList.flatMap(_.members.map(_.id)))

  private val english = Wording.english

  test("only an owner joins their group directly, or by inviting themselves"):
    withStores("join"): (groups, users, _) =>
      for
        owner    <- register(users, "owner")
        stranger <- register(users, "stranger")
        team     <- groups.create(owner.id, GroupDetails("Team"))
        mine     <- groups.create(owner.id, GroupDetails("Mine"))
        _        <- groups.join(owner.id, team.id)
        _        <- groups.join(owner.id, team.id)
        _        <- groups.invite(owner.id, mine.id, owner.username)
        refused  <- refusal(groups.join(stranger.id, team.id))
        members  <- membersOf(groups, owner, team.id)
        joined   <- membersOf(groups, owner, mine.id)
        pending  <- groups.invitations(owner.id)
      yield
        assertEquals(members, List(owner.id))
        assertEquals(joined, List(owner.id))
        assertEquals(pending, List.empty[Invitation])
        assertEquals(refused, Some(english.groupMissing))

  test("a member of a group may ask to join a group nested inside it"):
    withStores("nested-request"): (groups, users, _) =>
      for
        owner  <- register(users, "owner")
        member <- register(users, "member")
        dept   <- groups.create(owner.id, GroupDetails("Department"))
        team   <- groups.create(
          owner.id,
          GroupDetails("Team", Some(dept.id)),
        )
        _     <- enrol(groups, owner, dept.id, member)
        _     <- groups.request(member.id, team.id)
        _     <- groups.request(member.id, team.id)
        views <- groups.managed(owner.id)
        found <- groups.joinable(member.id)
      yield
        assertEquals(
          views.find(_.group.id == team.id).map(_.applicants.map(_.id)),
          Some(List(member.id)),
        )
        assertEquals(
          found.map(offer => (offer.group.id, offer.requested)),
          List(team.id -> true),
        )
        assertEquals(
          found.map(_.owners.map(_.principal)),
          List(List(Principal.Person(owner.id))),
        )

  test("admitting someone who asked makes them a member, and nobody else"):
    withStores("admit"): (groups, users, _) =>
      for
        owner    <- register(users, "owner")
        member   <- register(users, "member")
        stranger <- register(users, "stranger")
        team     <- groups.create(owner.id, GroupDetails("Team"))
        _        <- groups.setPublic(owner.id, team.id, true)
        _        <- groups.request(member.id, team.id)
        refused  <- refusal(groups.admit(owner.id, team.id, stranger.id))
        _        <- groups.admit(owner.id, team.id, member.id)
        views    <- groups.managed(owner.id)
        found    <- groups.joinable(member.id)
      yield
        val view = views.find(_.group.id == team.id).get
        assertEquals(refused, Some(english.requestMissing))
        assertEquals(
          view.members.map(_.id),
          List(member.id),
        )
        assertEquals(view.applicants, List.empty[User])
        assertEquals(found, List.empty)

  test("a stranger may ask to join a group its owner made public, not before"):
    withStores("public"): (groups, users, _) =>
      for
        owner    <- register(users, "owner")
        stranger <- register(users, "stranger")
        dept     <- groups.create(owner.id, GroupDetails("Department"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Team", Some(dept.id)),
        )
        before    <- groups.joinable(stranger.id)
        refused   <- refusal(groups.request(stranger.id, team.id))
        published <- refusal(groups.setPublic(stranger.id, team.id, true))
        still     <- groups.joinable(stranger.id)
        _         <- groups.setPublic(owner.id, team.id, true)
        after     <- groups.joinable(stranger.id)
        _         <- groups.request(stranger.id, team.id)
        views     <- groups.managed(owner.id)
      yield
        assertEquals(before, List.empty)
        assertEquals(refused, Some(english.groupMissing))
        // Only the owner may make a group public.
        assertEquals(published, Some(english.groupMissing))
        assertEquals(still, List.empty)
        assertEquals(after.map(_.group.id), List(team.id))
        assertEquals(after.map(_.group.public), List(true))
        // A public group nested in a private one hides its parent.
        assertEquals(
          after.map(_.group.parentId),
          List(None),
        )
        assertEquals(
          views.flatMap(_.applicants.map(_.id)),
          List(stranger.id),
        )

  test("groups the user owns, belongs to or is invited to are not joinable"):
    withStores("not-joinable"): (groups, users, _) =>
      for
        owner  <- register(users, "owner")
        member <- register(users, "member")
        mine   <- groups.create(member.id, GroupDetails("Mine"))
        joined <- groups.create(owner.id, GroupDetails("Joined"))
        asked  <- groups.create(owner.id, GroupDetails("Invited"))
        _      <- groups.setPublic(member.id, mine.id, true)
        _      <- groups.setPublic(owner.id, joined.id, true)
        _      <- groups.setPublic(owner.id, asked.id, true)
        _      <- enrol(groups, owner, joined.id, member)
        _      <- groups.invite(owner.id, asked.id, member.username)
        found  <- groups.joinable(member.id)
      yield assertEquals(found, List.empty)

  test("an invitation and a request to join make a member, whichever is first"):
    withStores("ask-invited"): (groups, users, _) =>
      for
        owner   <- register(users, "owner")
        invited <- register(users, "invited")
        asker   <- register(users, "asker")
        team    <- groups.create(owner.id, GroupDetails("Team"))
        _       <- groups.setPublic(owner.id, team.id, true)
        _       <- groups.invite(owner.id, team.id, invited.username)
        _       <- groups.request(invited.id, team.id)
        _       <- groups.request(asker.id, team.id)
        _       <- groups.invite(owner.id, team.id, asker.username)
        theirs  <- groups.invitations(invited.id)
        asked   <- groups.invitations(asker.id)
        members <- membersOf(groups, owner, team.id)
      yield
        assertEquals(theirs ++ asked, List.empty[Invitation])
        assertEquals(
          members.sorted,
          List(invited.id, asker.id).sorted,
        )

  test("a request withdrawn or declined is gone, and may be made again"):
    withStores("withdraw"): (groups, users, _) =>
      for
        owner    <- register(users, "owner")
        member   <- register(users, "member")
        team     <- groups.create(owner.id, GroupDetails("Team"))
        _        <- groups.setPublic(owner.id, team.id, true)
        _        <- groups.request(member.id, team.id)
        _        <- groups.withdraw(member.id, team.id)
        withdrew <- groups.managed(owner.id)
        _        <- groups.request(member.id, team.id)
        _        <- groups.remove(owner.id, team.id, member.id)
        declined <- groups.managed(owner.id)
        again    <- groups.joinable(member.id)
      yield
        assertEquals(
          withdrew.flatMap(_.applicants),
          List.empty[User],
        )
        assertEquals(
          declined.flatMap(_.applicants),
          List.empty[User],
        )
        assertEquals(again.map(_.requested), List(false))

  test("a group's invite link is made by its owner once, replaced, turned off"):
    withStores("group-link"): (groups, users, db) =>
      val links = LinkStore(TestDb.tables)
      for
        owner    <- register(users, "owner")
        stranger <- register(users, "stranger")
        team     <- groups.create(owner.id, GroupDetails("Team"))
        refused  <- refusal(groups.link(stranger.id, team.id))
        first    <- groups.link(owner.id, team.id)
        same     <- groups.link(owner.id, team.id)
        shown    <- groups.managed(owner.id)
        found    <- db.run(links.find(first))
        renewed  <- groups.relink(owner.id, team.id)
        _        <- groups.unlink(owner.id, team.id)
        gone     <- groups.managed(owner.id)
      yield
        assertEquals(refused, Some(english.groupMissing))
        assertEquals(same, first)
        assertEquals(InviteCode.parse(first), Some(first))
        assertEquals(
          shown.map(_.inviteCode),
          List(Some(first)),
        )
        assertEquals(
          found.flatMap(_._1.target),
          Some(LinkTarget.Joining(team.id)),
        )
        assertEquals(found.map(_._2.id), Some(owner.id))
        assertNotEquals(renewed, first)
        assertEquals(gone.map(_.inviteCode), List(None))

  test("deleting a group turns off its invite link and ends its requests"):
    withStores("delete-link"): (groups, users, db) =>
      val links = LinkStore(TestDb.tables)
      for
        owner  <- register(users, "owner")
        member <- register(users, "member")
        team   <- groups.create(owner.id, GroupDetails("Team"))
        _      <- groups.setPublic(owner.id, team.id, true)
        _      <- groups.request(member.id, team.id)
        code   <- groups.link(owner.id, team.id)
        _      <- groups.delete(owner.id, team.id)
        found  <- db.run(links.find(code))
        asked  <- groups.joinable(member.id)
      yield
        assertEquals(found, None)
        assertEquals(asked, List.empty)
