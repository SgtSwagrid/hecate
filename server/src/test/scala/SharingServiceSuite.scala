package com.alecdorrington.hecate
package server

import Fixtures.{cheap, enrol, grant}
import cats.effect.{IO, Ref}
import com.alecdorrington.hecate.model.{
  Access, AuthRefusal, Group, GroupDetails, InviteCode, Principal, Resource,
  User,
}
import munit.CatsEffectSuite
import slick.dbio.DBIO

/**
  * Tests of what [[SharingService]] answers: who may see and change who holds a
  * resource, whom it may be shared with, and its invite link, with a resource
  * never left without an owner.
  */
class SharingServiceSuite extends CatsEffectSuite:

  import SharingServiceSuite.*

  test("an owner shares with someone they may address, and withdraws it"):
    served(): world =>
      for
        owner   <- world.owner
        reader  <- world.signUp("reader")
        club    <- world.groups.create(owner.id, GroupDetails("Book club"))
        _       <- enrol(world.groups, owner, club.id, reader)
        _       <- world.service.grant(owner, book, reader.person, Access.View)
        _       <- world.service.grant(owner, book, reader.person, Access.Edit)
        raised  <- world.reported
        shared  <- world.service.list(owner, book)
        _       <- world.service.grant(owner, book, reader.person, Access.View)
        lowered <- world.reported
        _       <- world.service.revoke(owner, book, reader.person)
        after   <- world.service.list(owner, book)
        told    <- world.reported
      yield
        assertEquals(
          shared.map(_.map(grant => grant.principal -> grant.access).toSet),
          Right(Set(
            owner.person  -> Access.Own,
            reader.person -> Access.Edit,
          )),
        )
        assertEquals(
          after.map(_.map(_.principal)),
          Right(List(owner.person)),
        )
        // Those reached before are told only of a change taking access.
        assertEquals(
          raised,
          List(
            Affected.Grants(book),
            Affected.Grants(book),
          ),
        )
        assertEquals(
          lowered,
          List(Affected.Grants(book, Set(owner.id, reader.id))),
        )
        assertEquals(
          told,
          List(Affected.Grants(book, Set(owner.id, reader.id))),
        )

  test("only an owner sees who holds a resource, and others find it missing"):
    served(): world =>
      for
        owner    <- world.owner
        reader   <- world.signUp("reader")
        stranger <- world.signUp("stranger")
        _ <- grant(world.grants, world.db)(book, reader.person, Access.Edit)
        edited <- world.service.list(reader, book)
        unseen <- world.service.list(stranger, book)
        taken  <- world.service.grant(reader, book, reader.person, Access.Own)
        held   <- world.service.list(owner, book)
      yield
        assertEquals(
          edited,
          Left(AuthRefusal.NotOwner("book")),
        )
        assertEquals(
          unseen,
          Left(AuthRefusal.ResourceMissing("book")),
        )
        assertEquals(
          taken,
          Left(AuthRefusal.NotOwner("book")),
        )
        assertEquals(held.map(_.size), Right(2))

  test("a host's own access decides who may see a resource"):
    served(access = Some((user, _) => IO.pure(Some(Access.View)))): world =>
      for
        stranger <- world.signUp("stranger")
        seen     <- world.service.list(stranger, book)
      yield assertEquals(
        seen,
        Left(AuthRefusal.NotOwner("book")),
      )

  test("a resource is never left without an owner"):
    served(): world =>
      for
        owner   <- world.owner
        partner <- world.signUp("partner")
        club    <- world.groups.create(owner.id, GroupDetails("Book club"))
        _       <- enrol(world.groups, owner, club.id, partner)
        leaving <- world.service.revoke(owner, book, owner.person)
        lowered <- world.service.grant(owner, book, owner.person, Access.Edit)
        _       <- world.service.grant(owner, book, partner.person, Access.Own)
        handed  <- world.service.revoke(owner, book, owner.person)
        held    <- world.service.list(partner, book)
      yield
        assertEquals(
          leaving,
          Left(AuthRefusal.LastOwner("book")),
        )
        assertEquals(
          lowered,
          Left(AuthRefusal.LastOwner("book")),
        )
        assertEquals(handed, Right(()))
        assertEquals(
          held.map(_.map(_.principal)),
          Right(List(partner.person)),
        )

  test("nothing is shared with someone the owner may not address"):
    served(): world =>
      for
        owner    <- world.owner
        stranger <- world.signUp("stranger")
        refused  <- world
          .service
          .grant(
            owner,
            book,
            stranger.person,
            Access.View,
          )
        foreign <- world
          .service
          .grant(
            owner,
            book,
            Principal.Group(404),
            Access.View,
          )
        held <- world.service.list(owner, book)
      yield
        assertEquals(
          refused,
          Left(AuthRefusal.PrincipalMissing),
        )
        assertEquals(
          foreign,
          Left(AuthRefusal.PrincipalMissing),
        )
        assertEquals(held.map(_.size), Right(1))

  test("a link grants the access asked for, keeps its code, and never owns"):
    served(): world =>
      for
        owner <- world.owner
        _     <- grant(world.grants, world.db)(lost, owner.person, Access.Own)
        made  <- world.service.setLink(owner, lost, Access.View)
        shownLost <- world.service.link(owner, lost)
        first     <- world.service.setLink(owner, book, Access.View)
        edited    <- world.service.setLink(owner, book, Access.Edit)
        owning    <- world.service.setLink(owner, book, Access.Own)
        renewed   <- world.service.relink(owner, book)
        shown     <- world.service.link(owner, book)
        _         <- world.service.unlink(owner, book)
        gone      <- world.service.link(owner, book)
      yield
        assertEquals(
          first.map(_.access),
          Right(Access.View),
        )
        assertEquals(
          first.toOption.flatMap(link => InviteCode.parse(link.code)),
          first.toOption.map(_.code),
        )
        assertEquals(edited.map(_.code), first.map(_.code))
        assertEquals(
          owning,
          Left(AuthRefusal.OwnershipByLink),
        )
        assertEquals(
          renewed.map(_.access),
          Right(Access.Edit),
        )
        assertNotEquals(renewed.map(_.code), first.map(_.code))
        assertEquals(shown, renewed.map(Some(_)))
        assertEquals(gone, Right(None))
        // Nothing links to a resource that no longer exists.
        assertEquals(
          made,
          Left(AuthRefusal.ResourceMissing("book")),
        )
        assertEquals(shownLost, Right(None))

  test("what a deleted group alone owned passes to whoever deleted it"):
    served(): world =>
      for
        owner  <- world.owner
        reader <- world.signUp("reader")
        club   <- world.groups.create(owner.id, GroupDetails("Book club"))
        _      <- enrol(world.groups, owner, club.id, reader)
        _      <- world.service.grant(owner, book, club.principal, Access.Own)
        _      <- world.service.revoke(owner, book, owner.person)
        _      <- world.reported
        gone   <- world.groupService.delete(owner, club.id)
        held   <- world.service.list(owner, book)
        told   <- world.reported
      yield
        assertEquals(gone, Right(()))
        assertEquals(
          held.map(_.map(grant => grant.principal -> grant.access)),
          Right(List(owner.person -> Access.Own)),
        )
        assert(
          told.contains(Affected.Grants(book, Set(reader.id))),
          clue(told),
        )

  test("what nested groups owned between them passes to their owner"):
    served(): world =>
      for
        owner <- world.owner
        club  <- world.groups.create(owner.id, GroupDetails("Book club"))
        panel <- world
          .groups
          .create(
            owner.id,
            GroupDetails("Panel", Some(club.id)),
          )
        _    <- world.service.grant(owner, book, club.principal, Access.Own)
        _    <- world.service.grant(owner, book, panel.principal, Access.Own)
        _    <- world.service.revoke(owner, book, owner.person)
        _    <- world.groups.delete(owner.id, panel.id)
        kept <- world.service.list(owner, book)
        _    <- world.groups.delete(owner.id, club.id)
        held <- world.service.list(owner, book)
      yield
        assertEquals(
          kept,
          Left(AuthRefusal.ResourceMissing("book")),
        )
        assertEquals(
          held.map(_.map(grant => grant.principal -> grant.access)),
          Right(List(owner.person -> Access.Own)),
        )

  test("a deleted group's resources pass to nobody while another owns them"):
    served(): world =>
      for
        owner   <- world.owner
        partner <- world.signUp("partner")
        club    <- world.groups.create(owner.id, GroupDetails("Book club"))
        _       <- enrol(world.groups, owner, club.id, partner)
        _       <- world.service.grant(owner, book, club.principal, Access.Own)
        _       <- world.service.grant(owner, book, partner.person, Access.Own)
        _       <- world.service.revoke(owner, book, owner.person)
        // A resource that no longer exists passes to nobody either.
        _ <- grant(world.grants, world.db)(lost, club.principal, Access.Own)
        handed <- world.groups.delete(owner.id, club.id)
        held   <- world.service.list(partner, book)
        gone   <- world.grants.over(lost)
      yield
        assertEquals(handed, List.empty)
        assertEquals(
          held.map(_.map(_.principal)),
          Right(List(partner.person)),
        )
        assertEquals(gone, List.empty)

object SharingServiceSuite:

  /** The resource the tests share, a book. */
  private val book = Resource("book", 1)

  /** A book that has been deleted, though a grant over it remains. */
  private val lost = Resource("book", 2)

  /** The names of the host's resources: the one book that still exists. */
  private val names = Map(book -> "Dune")

  /** The host's lookup of a resource's name, which would lock its row. */
  private val named: Resource => DBIO[Option[String]] =
    resource => DBIO.successful(names.get(resource))

  extension (user: User)

    /** The user as a principal. */
    private def person: Principal = Principal.Person(user.id)

  extension (group: Group)

    /** The group as a principal. */
    private def principal: Principal = Principal.Group(group.id)

  /**
    * The service under test and the stores beneath it, over one fresh database,
    * with whatever it reported as affected.
    */
  private final class World
    (
      val db: TestDb,
      val groups: GroupStore,
      val service: SharingService,
      auth: AuthService,
      told: Ref[IO, List[Affected]],
    ):

    val grants = GrantStore(TestDb.tables, db)

    /** The group service, telling whom its changes concern as the service does. */
    val groupService = GroupService(
      groups,
      affected = change => told.update(change :: _),
    )

    /** Registers a user through the auth service. */
    def signUp(name: String): IO[User] = Fixtures.signUp(auth, name).map(_._1)

    /** Registers the owner of [[book]]. */
    def owner: IO[User] = signUp("owner").flatTap(owner =>
      grant(grants, db)(book, owner.person, Access.Own),
    )

    /** Everything reported as affected since last asked, oldest first. */
    def reported: IO[List[Affected]] = told.getAndSet(List.empty).map(_.reverse)

  /** Runs a check against the service over a fresh database. */
  private def served
    (access: Option[(User, Resource) => IO[Option[Access]]] = None)
    (check: World => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"sharing-${ java.util.UUID.randomUUID }")
    .use: db =>
      Ref
        .of[IO, List[Affected]](List.empty)
        .flatMap: told =>
          val groups = GroupStore(TestDb.tables, db, resources = named)
          val grants = GrantStore(TestDb.tables, db)
          check(World(
            db,
            groups,
            SharingService(
              grants,
              LinkStore(TestDb.tables),
              groups,
              db,
              named,
              access = access,
              affected = change => told.update(change :: _),
            ),
            AuthService(UserStore(TestDb.tables, db), cheap),
            told,
          ))
