package com.alecdorrington.hecate
package server

import Fixtures.{enrol, grant, reach, register}
import TestDb.tables.profile.api.*
import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, Grant, GroupDetails, Principal, Resource,
}
import munit.CatsEffectSuite

class GrantStoreSuite extends CatsEffectSuite:

  private val document      = Resource("document", 1)
  private val otherDocument = Resource("document", 2)

  private def withStore
    (name: String)
    (check: (GrantStore, TestDb) => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"grants-$name")
    .use(db => check(GrantStore(TestDb.tables, db), db))

  test("access is the highest grant reaching one's principals, and no other"):
    withStore("access"): (grants, db) =>
      val unreadable = Resource("document", 3)
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.Own,
        )
        // A stored level this version cannot read confers nothing.
        _ <- db.run(
          TestDb.tables.grants += GrantRow(
            0,
            "document",
            unreadable.id,
            "person",
            1,
            "admin",
          ),
        )
        highest   <- grants.access(reach(1, 10), document)
        personal  <- grants.access(reach(1), document)
        member    <- grants.access(reach(2, 10), document)
        outside   <- grants.access(reach(2, 11), document)
        namesake  <- grants.access(reach(10), document)
        ungranted <- grants.access(reach(1, 10), otherDocument)
        unread    <- grants.access(reach(1), unreadable)
        over      <- grants.over(unreadable)
      yield
        assertEquals(highest, Some(Access.Own))
        assertEquals(personal, Some(Access.View))
        assertEquals(member, Some(Access.Own))
        // A user and a group sharing an identifier are not confused.
        assertEquals(
          (outside, namesake, ungranted, unread),
          (None, None, None, None),
        )
        assertEquals(over, List.empty)

  test("regranting replaces one's access, and revoking it spares others"):
    withStore("regrant"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Person(2),
          Access.Edit,
        )
        access <- grants.access(reach(1), document)
        all    <- grants.over(document)
        _      <- db.run(grants.revoke(document, Principal.Person(1)))
        gone   <- grants.access(reach(1), document)
        kept   <- grants.access(reach(2), document)
      yield
        assertEquals(access, Some(Access.View))
        assertEquals(
          all.toSet,
          Set(
            Grant(
              document,
              Principal.Person(1),
              Access.View,
            ),
            Grant(
              document,
              Principal.Person(2),
              Access.Edit,
            ),
          ),
        )
        assertEquals(gone, None)
        assertEquals(kept, Some(Access.Edit))

  test("revoking over a resource, or what principals hold, spares the rest"):
    withStore("revoke"): (grants, db) =>
      val thirdDocument = Resource("document", 3)
      for
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.Edit,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(11),
          Access.View,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Person(10),
          Access.Edit,
        )
        _ <- grant(grants, db)(
          thirdDocument,
          Principal.Person(1),
          Access.Own,
        )
        _         <- db.run(grants.revokeHeldBy(List.empty))
        untouched <- grants.over(document)
        _         <- db.run(grants.revokeHeldBy(List(Principal.Group(10))))
        held      <- grants.over(document)
        other     <- grants.over(otherDocument)
        _         <- db.run(grants.revokeOver(document))
        gone      <- grants.over(document)
        kept      <- grants.over(thirdDocument)
      yield
        assertEquals(untouched.size, 3)
        assertEquals(
          held.map(_.principal).toSet,
          Set(
            Principal.Group(11),
            Principal.Person(10),
          ),
        )
        assertEquals(other, List.empty)
        assertEquals(gone, List.empty)
        assertEquals(
          kept.map(_.principal),
          List(Principal.Person(1)),
        )

  test("the grants over several resources are read in one, and no others"):
    withStore("over-all"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.View,
        )
        _ <- grant(grants, db)(
          Resource("document", 3),
          Principal.Person(2),
          Access.Own,
        )
        _ <- grant(grants, db)(
          Resource("folder", 1),
          Principal.Person(3),
          Access.Own,
        )
        both <- grants.overAll("document", List(1L, 2L, 2L))
        none <- grants.overAll("document", Nil)
      yield
        assertEquals(
          both.toSet,
          Set(
            Grant(
              document,
              Principal.Person(1),
              Access.Own,
            ),
            Grant(
              otherDocument,
              Principal.Group(10),
              Access.View,
            ),
          ),
        )
        assertEquals(none, Nil)

  test("a kind's resources are listed by the highest access held over each"):
    withStore("accessible"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.Own,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.Edit,
        )
        _ <- grant(grants, db)(
          Resource("folder", 3),
          Principal.Person(1),
          Access.Own,
        )
        held   <- grants.levels(reach(1, 10), "document")
        owns   <- grants.accessible(reach(1, 10), "document", Access.Own)
        edits  <- grants.accessible(reach(1, 10), "document", Access.Edit)
        views  <- grants.accessible(reach(1), "document", Access.View)
        others <- grants.accessible(reach(2), "document", Access.View)
      yield
        assertEquals(
          held,
          Map(1L -> Access.Own, 2L -> Access.Edit),
        )
        assertEquals(owns, Set(1L))
        assertEquals(edits, Set(1L, 2L))
        assertEquals(views, Set(1L))
        assertEquals(others, Set.empty[Long])

  test("a grant to an enclosing group reaches nested members, not the reverse"):
    TestDb
      .open("grants-nesting")
      .use: db =>
        val groups      = GroupStore(TestDb.tables, db)
        val users       = UserStore(TestDb.tables, db)
        val grants      = GrantStore(TestDb.tables, db)
        val permissions = Permissions(groups, grants)
        for
          owner <- register(users, "manager")
          inner <- register(users, "inner")
          outer <- register(users, "outer")
          dept  <- groups.create(owner.id, GroupDetails("Sales"))
          team  <- groups.create(
            owner.id,
            GroupDetails("Retail", Some(dept.id)),
          )
          _ <- enrol(groups, owner, team.id, inner)
          _ <- enrol(groups, owner, dept.id, outer)
          _ <- grant(grants, db)(
            document,
            Principal.Group(dept.id),
            Access.View,
          )
          _ <- grant(grants, db)(
            otherDocument,
            Principal.Group(team.id),
            Access.Edit,
          )
          down <- permissions.access(inner.id, document)
          up   <- permissions.access(outer.id, otherDocument)
        yield
          assertEquals(down, Some(Access.View))
          assertEquals(up, None)

  test("an invitation, pending or declined, never satisfies a group grant"):
    TestDb
      .open("grants-invited")
      .use: db =>
        val groups      = GroupStore(TestDb.tables, db)
        val users       = UserStore(TestDb.tables, db)
        val grants      = GrantStore(TestDb.tables, db)
        val permissions = Permissions(groups, grants)
        for
          owner    <- register(users, "manager")
          employee <- register(users, "employee")
          team     <- groups.create(owner.id, GroupDetails("Retail"))
          _        <- grant(grants, db)(
            document,
            Principal.Group(team.id),
            Access.View,
          )
          _        <- groups.invite(owner.id, team.id, employee.username)
          pending  <- permissions.access(employee.id, document)
          invited  <- groups.invitations(employee.id)
          _        <- groups.decline(employee.id, invited.head.id)
          declined <- permissions.access(employee.id, document)
          _        <- groups.invite(owner.id, team.id, employee.username)
          again    <- groups.invitations(employee.id)
          _        <- groups.accept(employee.id, again.head.id)
          accepted <- permissions.access(employee.id, document)
        yield
          assertEquals(pending, None)
          assertEquals(declined, None)
          assertEquals(accepted, Some(Access.View))

  test("one owns solely what nobody else owns, and alone what nobody holds"):
    withStore("sole"): (grants, db) =>
      val give_         = grant(grants, db)
      val thirdDocument = Resource("document", 3)
      val notes         = Resource("note", 1)
      val shared        = Resource("folder", 7)
      for
        _ <- give_(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give_(
          otherDocument,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give_(
          otherDocument,
          Principal.Person(2),
          Access.Own,
        )
        _ <- give_(
          thirdDocument,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give_(
          thirdDocument,
          Principal.Group(2),
          Access.View,
        )
        _ <- give_(
          Resource("folder", 5),
          Principal.Person(1),
          Access.Edit,
        )
        _ <- give_(
          Resource("folder", 6),
          Principal.Person(3),
          Access.Own,
        )
        // A group holding Own counts as another owner.
        _     <- give_(shared, Principal.Person(1), Access.Own)
        _     <- give_(shared, Principal.Group(1), Access.Own)
        _     <- give_(notes, Principal.Person(1), Access.Own)
        sole  <- db.run(grants.ownedSolelyBy(Seq(Principal.Person(1))))
        alone <- db.run(grants.ownedAlone(1, "document").result)
        none  <- db.run(grants.ownedAlone(2, "document").result)
      yield
        assertEquals(
          sole.toSet,
          Set(document, thirdDocument, notes),
        )
        assertEquals(alone, Seq(document.id))
        assertEquals(none, Seq.empty)

  test("a transaction reads the grants over a resource, its own included"):
    withStore("granted"): (grants, db) =>
      val third = Resource("document", 3)
      val write = grants.grant(Grant(third, Principal.Person(1), Access.Edit))
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(2),
          Access.View,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Person(3),
          Access.Edit,
        )
        inside   <- db.run(grants.granted(document))
        beside   <- grants.over(document)
        composed <- db.run(write.andThen(grants.granted(third)).transactionally)
      yield
        assertEquals(inside.toSet, beside.toSet)
        assertEquals(
          inside.map(_.principal).toSet,
          Set(
            Principal.Person(1),
            Principal.Group(2),
          ),
        )
        assertEquals(
          composed.map(_.access),
          List(Access.Edit),
        )
