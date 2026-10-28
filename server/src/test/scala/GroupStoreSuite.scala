package com.alecdorrington.hecate
package server

import Fixtures.{enrol, grant, invitationTo, refusal, register}
// The tables' own profile: a concrete profile's `api` would bring a second,
// clashing set of extension methods into scope.
import TestDb.tables.profile.api.*
import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{
  Access, GroupDetails, Holder, Principal, Resource,
}
import munit.CatsEffectSuite

class GroupStoreSuite extends CatsEffectSuite:

  private def withStores
    (name: String)
    (check: (GroupStore, UserStore) => IO[Unit])
    : IO[Unit] = TestDb.groups(s"groups-$name").use(check.tupled)

  test("created groups are listed back for their owner, nested, and empty"):
    withStores("create"): (groups, users) =>
      for
        owner <- register(users, "manager")
        dept  <- groups.create(owner.id, GroupDetails("Sales"))
        team  <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _ <- groups.create(
          owner.id,
          GroupDetails("Front desk", Some(team.id)),
        )
        all <- groups.managed(owner.id)
      yield
        assertEquals(
          all.map(_.group).headOption,
          Some(dept),
        )
        assertEquals(
          all.map(view => (view.group.name, view.group.parentId)),
          List(
            ("Sales", None),
            ("Retail", Some(dept.id)),
            ("Front desk", Some(team.id)),
          ),
        )
        assertEquals(all.flatMap(_.members), List.empty)
        assertEquals(all.flatMap(_.invitees), List.empty)

  test("updating renames and moves a group, but never inside its own subtree"):
    withStores("update"): (groups, users) =>
      for
        owner <- register(users, "manager")
        dept  <- groups.create(owner.id, GroupDetails("Sales"))
        team  <- groups.create(owner.id, GroupDetails("Retail"))
        _     <- groups.update(
          owner.id,
          team.id,
          GroupDetails("Outlets", Some(dept.id)),
        )
        all  <- groups.managed(owner.id)
        onto <- groups
          .update(
            owner.id,
            dept.id,
            GroupDetails("Sales", Some(team.id)),
          )
          .attempt
        ontoSelf <- groups
          .update(
            owner.id,
            dept.id,
            GroupDetails("Sales", Some(dept.id)),
          )
          .attempt
      yield
        assertEquals(
          all.map(view => (view.group.name, view.group.parentId)),
          List(
            ("Sales", None),
            ("Outlets", Some(dept.id)),
          ),
        )
        assert(onto.isLeft)
        assert(ontoSelf.isLeft)

  test("deleting a group deletes its subtree, members, invitations and grants"):
    TestDb
      .open("groups-delete")
      .use: db =>
        var cascaded  = List.empty[Long]
        var surviving = 0
        val groups    = GroupStore(
          TestDb.tables,
          db,
          principals =>
            val ids = principals.collect { case Principal.Group(id) => id }
            // The groups must still exist while the cascade runs.
            TestDb
              .tables
              .groups
              .filter(_.id inSet ids)
              .length
              .result
              .map: present =>
                cascaded = ids.toList
                surviving = present,
        )
        val users    = UserStore(TestDb.tables, db)
        val grants   = GrantStore(TestDb.tables, db)
        val document = Resource("document", 1)
        for
          owner    <- register(users, "manager")
          employee <- register(users, "employee")
          invited  <- register(users, "invited")
          dept     <- groups.create(owner.id, GroupDetails("Sales"))
          team     <- groups.create(
            owner.id,
            GroupDetails("Retail", Some(dept.id)),
          )
          keep <- groups.create(owner.id, GroupDetails("Chess club"))
          _    <- enrol(groups, owner, team.id, employee)
          _    <- groups.invite(owner.id, team.id, "invited")
          _    <- grant(grants, db)(
            document,
            Principal.Group(team.id),
            Access.View,
          )
          _ <- grant(grants, db)(
            document,
            Principal.Group(keep.id),
            Access.View,
          )
          _       <- groups.delete(owner.id, dept.id)
          all     <- groups.managed(owner.id)
          theirs  <- groups.enclosing(employee.id)
          pending <- groups.invitations(invited.id)
          left    <- grants.over(document)
        yield
          assertEquals(all.map(_.group), List(keep))
          assertEquals(theirs, List.empty)
          assertEquals(pending, List.empty)
          assertEquals(
            left.map(_.principal),
            List(Principal.Group(keep.id)),
          )
          // The cascade sees the whole subtree, before it is deleted.
          assertEquals(
            cascaded.sorted,
            List(dept.id, team.id).sorted,
          )
          assertEquals(surviving, 2)

  test(
    "an invited user is an invitee, not a member, and is reached by nothing",
  ):
    withStores("invite"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "Employee")
        // Inviting the same user twice makes one invitation.
        _       <- groups.invite(owner.id, team.id, "employee")
        unknown <- refusal(groups.invite(owner.id, team.id, "nobody"))
        view    <- groups.managed(owner.id).map(_.head)
        sent    <- groups.invitations(employee.id)
        reached <- groups.enclosing(employee.id)
        joined  <- groups.memberships(employee.id)
      yield
        assertEquals(view.members, List.empty)
        assertEquals(view.invitees, List(employee))
        assertEquals(sent.map(_.group.id), List(team.id))
        assertEquals(sent.map(_.inviter), List(Some(owner)))
        assertEquals(reached, List.empty)
        assertEquals(joined, List.empty)
        assertEquals(
          unknown,
          Some("No user is called \"nobody\"."),
        )

  test("accepting an invitation makes a member, whom inviting changes no more"):
    withStores("accept"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _    <- enrol(groups, owner, team.id, employee)
        _    <- groups.invite(owner.id, team.id, "employee")
        view <- groups.managed(owner.id).map(_.find(_.group.id == team.id).get)
        sent <- groups.invitations(employee.id)
        reached <- groups.enclosing(employee.id)
      yield
        assertEquals(view.members, List(employee))
        assertEquals(view.invitees, List.empty)
        assertEquals(sent, List.empty)
        assertEquals(reached, List(dept.id, team.id).sorted)

  test("an invitation can be accepted only once, and only by its invitee"):
    withStores("accept-once"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        stranger <- register(users, "stranger")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- invitationTo(groups, employee, team.id)
        stolen   <- refusal(groups.accept(stranger.id, sent.id))
        _        <- groups.accept(employee.id, sent.id)
        again    <- refusal(groups.accept(employee.id, sent.id))
        members  <- groups.managed(owner.id).map(_.flatMap(_.members))
      yield
        assertEquals(
          stolen,
          Some("That invitation doesn't exist."),
        )
        assertEquals(
          again,
          Some("That invitation doesn't exist."),
        )
        assertEquals(members, List(employee))

  test("a declined invitation is gone, never accepted, and may be sent again"):
    withStores("decline"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- invitationTo(groups, employee, team.id)
        _        <- groups.decline(employee.id, sent.id)
        declined <- groups.managed(owner.id).map(_.head.invitees)
        held     <- groups.invitations(employee.id)
        late     <- refusal(groups.accept(employee.id, sent.id))
        _        <- groups.invite(owner.id, team.id, "employee")
        again    <- groups.managed(owner.id).map(_.head)
      yield
        assertEquals(declined, List.empty)
        assertEquals(held, List.empty)
        assertEquals(
          late,
          Some("That invitation doesn't exist."),
        )
        assertEquals(again.members, List.empty)
        assertEquals(again.invitees, List(employee))

  test("leaving ends a membership, seen or addressed no more, until invited"):
    withStores("leave"): (groups, users) =>
      for
        owner  <- register(users, "manager")
        alice  <- register(users, "alice")
        bob    <- register(users, "bob")
        other  <- register(users, "other")
        team   <- groups.create(owner.id, GroupDetails("Retail"))
        _      <- enrol(groups, owner, team.id, alice)
        _      <- enrol(groups, owner, team.id, bob)
        before <- groups.mayAddress(owner.id, Principal.Person(bob.id))
        _      <- groups.leave(bob.id, team.id)
        // Leaving a group one is not in changes nothing.
        _       <- groups.leave(other.id, team.id)
        joined  <- groups.memberships(bob.id)
        reached <- groups.enclosing(bob.id)
        seen    <- groups.memberships(alice.id)
        after   <- groups.mayAddress(owner.id, Principal.Person(bob.id))
        _       <- groups.invite(owner.id, team.id, "bob")
        view    <- groups.managed(owner.id).map(_.head)
      yield
        assertEquals((before, after), (true, false))
        assertEquals(joined, List.empty)
        assertEquals(reached, List.empty)
        assertEquals(seen.flatMap(_.members), List(alice))
        assertEquals(view.members, List(alice))
        assertEquals(view.invitees, List(bob))

  test("an owner removes a member or an invitee, who may be invited again"):
    withStores("remove"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        invited  <- register(users, "invited")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- enrol(groups, owner, team.id, employee)
        _        <- groups.invite(owner.id, team.id, "invited")
        _        <- groups.remove(owner.id, team.id, employee.id)
        _        <- groups.remove(owner.id, team.id, invited.id)
        removed  <- groups.managed(owner.id).map(_.head)
        sent     <- groups.invitations(invited.id)
        _        <- groups.invite(owner.id, team.id, "employee")
        again    <- groups.managed(owner.id).map(_.head.invitees)
      yield
        assertEquals(removed.members, List.empty)
        assertEquals(removed.invitees, List.empty)
        assertEquals(sent, List.empty)
        assertEquals(again, List(employee))

  test("a member's effective groups are every ancestor of their own, no other"):
    withStores("ancestors"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        stranger <- register(users, "stranger")
        employee <- register(users, "employee")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        row <- groups.create(
          owner.id,
          GroupDetails("Front desk", Some(team.id)),
        )
        _ <- groups.create(owner.id, GroupDetails("Chess club"))
        // Another owner's forest, which the walk must neither read nor reach.
        theirs <- groups.create(stranger.id, GroupDetails("Accounts"))
        _      <- groups.create(
          stranger.id,
          GroupDetails("Payroll", Some(theirs.id)),
        )
        _     <- enrol(groups, owner, row.id, employee)
        mine  <- groups.enclosing(employee.id)
        none  <- groups.enclosing(stranger.id)
        named <- groups.names(mine :+ 999L)
      yield
        assertEquals(
          mine,
          List(dept.id, team.id, row.id).sorted,
        )
        assertEquals(none, List.empty)
        assertEquals(
          named,
          Map(
            dept.id -> "Sales",
            team.id -> "Retail",
            row.id  -> "Front desk",
          ),
        )

  test("a membership of a group that no longer exists reaches nothing"):
    TestDb
      .open("groups-orphan")
      .use: db =>
        val groups = GroupStore(TestDb.tables, db)
        val users  = UserStore(TestDb.tables, db)
        for
          owner    <- register(users, "manager")
          employee <- register(users, "employee")
          team     <- groups.create(owner.id, GroupDetails("Retail"))
          _        <- enrol(groups, owner, team.id, employee)
          // Deleted behind the store's back, leaving a dangling membership.
          _ <- db.run(TestDb.tables.groups.filter(_.id === team.id).delete)
          reached <- groups.enclosing(employee.id)
        yield assertEquals(reached, List.empty)

  test("a member sees the group they joined, its enclosure, and its members"):
    withStores("memberships"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        upper    <- register(users, "upper")
        company  <- groups.create(owner.id, GroupDetails("Company"))
        dept     <- groups.create(
          owner.id,
          GroupDetails("Sales", Some(company.id)),
        )
        team <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _     <- groups.create(owner.id, GroupDetails("Chess club"))
        _     <- enrol(groups, owner, team.id, employee)
        _     <- enrol(groups, owner, dept.id, upper)
        _     <- groups.join(owner.id, company.id)
        mine  <- groups.memberships(employee.id)
        above <- groups.memberships(upper.id)
      yield
        // Only the group joined: they cannot leave an enclosing group.
        assertEquals(mine.map(_.group), List(team))
        assertEquals(
          mine.map(_.enclosing.map(_.name)),
          List(List("Company", "Sales")),
        )
        // Neither a subgroup's members nor an enclosing group's.
        assertEquals(
          mine.flatMap(_.members.map(_.id)),
          List(employee.id),
        )
        assertEquals(above.flatMap(_.members), List(upper))

  test("the members of a group see its owner and one another"):
    withStores("fellows"): (groups, users) =>
      for
        owner   <- register(users, "manager")
        alice   <- register(users, "alice")
        bob     <- register(users, "bob")
        invitee <- register(users, "invitee")
        asker   <- register(users, "asker")
        outside <- register(users, "outsider")
        team    <- groups.create(owner.id, GroupDetails("Retail"))
        other   <- groups.create(owner.id, GroupDetails("Chess club"))
        _       <- groups.setPublic(owner.id, team.id, public = true)
        _       <- enrol(groups, owner, team.id, alice)
        _       <- enrol(groups, owner, team.id, bob)
        _       <- enrol(groups, owner, other.id, outside)
        _       <- groups.invite(owner.id, team.id, invitee.username)
        _       <- groups.request(asker.id, team.id)
        seen    <- groups.memberships(alice.id)
        mirror  <- groups.memberships(bob.id)
        pending <- groups.memberships(invitee.id)
      yield
        assertEquals(seen.map(_.group.id), List(team.id))
        assertEquals(
          seen.map(_.owners),
          List(List(Holder(
            Principal.Person(owner.id),
            Some(owner.username),
          ))),
        )
        // Members only: neither the invitee nor the asker has joined, and
        // the member of another group is none of theirs.
        assertEquals(
          seen.flatMap(_.members),
          List(alice, bob),
        )
        assertEquals(mirror, seen)
        assertEquals(pending, List.empty)

  test("a user may address themselves, their groups and their members, once"):
    withStores("addressable"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        member   <- register(users, "member")
        invited  <- register(users, "invited")
        stranger <- register(users, "stranger")
        other    <- register(users, "other")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        theirs <- groups.create(other.id, GroupDetails("Chess club"))
        _      <- enrol(groups, owner, team.id, member)
        _      <- groups.invite(owner.id, team.id, "invited")
        _      <- enrol(groups, other, theirs.id, stranger)
        // An owner in their own group is listed once.
        _      <- groups.join(owner.id, team.id)
        listed <- groups.addressable(owner.id)
        candidates = List(
          Principal.Person(owner.id),
          Principal.Person(member.id),
          Principal.Person(invited.id),
          Principal.Person(stranger.id),
          Principal.Group(dept.id),
          Principal.Group(team.id),
          Principal.Group(theirs.id),
        )
        answers <- candidates.traverse(groups.mayAddress(owner.id, _))
      yield
        assertEquals(
          listed,
          List(
            Principal.Person(owner.id),
            Principal.Group(dept.id),
            Principal.Group(team.id),
            Principal.Person(member.id),
          ),
        )
        // Whether a user may address someone agrees with the list.
        assertEquals(
          candidates.zip(answers).toMap,
          Map(
            Principal.Person(owner.id)    -> true,
            Principal.Person(member.id)   -> true,
            Principal.Person(invited.id)  -> false,
            Principal.Person(stranger.id) -> false,
            Principal.Group(dept.id)      -> true,
            Principal.Group(team.id)      -> true,
            Principal.Group(theirs.id)    -> false,
          ),
        )
        assertEquals(
          candidates.filter(listed.contains),
          candidates
            .zip(answers)
            .collect { case (principal, true) => principal },
        )
