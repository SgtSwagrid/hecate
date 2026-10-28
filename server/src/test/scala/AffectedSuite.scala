package com.alecdorrington.hecate
package server

import Fixtures.{cheap, enrol}
import cats.effect.{IO, Ref}
import com.alecdorrington.hecate.model.{
  Access, GroupDetails, Invitee, LinkTarget, PasswordChange, Resource, User,
}
import munit.CatsEffectSuite
import slick.dbio.DBIO

/** Tests of whom the services' `affected` hook says each change concerns. */
class AffectedSuite extends CatsEffectSuite:

  import AffectedSuite.*

  test("joining or leaving a group concerns its members, who see one another"):
    served: world =>
      for
        owner    <- world.signUp("owner")
        member   <- world.signUp("member")
        leaver   <- world.signUp("leaver")
        outsider <- world.signUp("outsider")
        team     <- world.groups.create(owner.id, GroupDetails("Team"))
        _        <- world.join(owner, team.id, member)
        _        <- world.join(owner, team.id, leaver)
        other    <- world.groups.create(owner.id, GroupDetails("Other"))
        code     <- world.groups.link(owner.id, other.id)
        _        <- world.linking.follow(outsider, code)
        _        <- world.reported
        _        <- world.service.leave(leaver, team.id)
        told     <- world.reported
      yield assertEquals(
        told,
        List(Affected.Groups(
          Audience.People(Set(owner.id, member.id, leaver.id)),
          Set(team.id),
        )),
      )

  test("following a link concerns a group's owner, joiner and enclosure"):
    served: world =>
      for
        owner   <- world.signUp("owner")
        other   <- world.signUp("other")
        joiner  <- world.signUp("joiner")
        company <- world.groups.create(owner.id, GroupDetails("Company"))
        _       <- world.groups.invite(owner.id, company.id, other.username)
        team    <- world
          .groups
          .create(
            owner.id,
            GroupDetails("Team", Some(company.id)),
          )
        code <- world.groups.link(owner.id, team.id)
        _    <- world.reported
        _    <- world.linking.follow(joiner, code)
        told <- world.reported
        // Following a link to a resource changes its grants.
        shared <- world
          .db
          .run(
            world
              .links
              .ensure(
                owner.id,
                LinkTarget.Sharing(book, Access.View),
              ),
          )
        _       <- world.linking.follow(joiner, shared)
        granted <- world.reported
      yield
        assertEquals(
          told,
          List(Affected.Groups(
            Audience.People(Set(owner.id, joiner.id)),
            Set(team.id, company.id),
          )),
        )
        assertEquals(granted, List(Affected.Grants(book)))

  test(
    "inviting someone reports the invitation beside the change to the group",
  ):
    served: world =>
      for
        owner   <- world.signUp("owner")
        invitee <- world.signUp("invitee")
        team    <- world.groups.create(owner.id, GroupDetails("Team"))
        _       <- world.reported
        _       <- world
          .service
          .invite(
            owner,
            team.id,
            Invitee(invitee.username),
          )
        told <- world.reported
        sent <- world.groups.invitations(invitee.id)
        // Inviting someone already invited, or oneself, reports no invitation.
        _ <- world
          .service
          .invite(
            owner,
            team.id,
            Invitee(invitee.username),
          )
        _     <- world.service.invite(owner, team.id, Invitee(owner.username))
        again <- world.reported
      yield
        assert(told.headOption.exists(_.isInstanceOf[Affected.Groups]))
        assertEquals(
          invitations(told),
          List(Affected.Invited(sent.head.id, team.id, invitee.id)),
        )
        assertEquals(invitations(again), List.empty)

  test("renaming a group concerns whoever sees it, and not whoever is in it"):
    served: world =>
      for
        owner   <- world.signUp("owner")
        member  <- world.signUp("member")
        invitee <- world.signUp("invitee")
        _       <- world.signUp("stranger")
        company <- world.groups.create(owner.id, GroupDetails("Company"))
        team    <- world
          .groups
          .create(
            owner.id,
            GroupDetails("Team", Some(company.id)),
          )
        _ <- world.join(owner, company.id, member)
        _ <- world.groups.invite(owner.id, team.id, invitee.username)
        _ <- world.reported
        _ <- world
          .service
          .update(
            owner,
            team.id,
            GroupDetails("Squad", Some(company.id)),
          )
        told <- world.reported
      yield assertEquals(
        told,
        List(Affected.Groups(
          Audience.People(Set(owner.id, member.id, invitee.id)),
          Set(team.id, company.id),
        )),
      )

  test("a group anyone may find concerns everyone, as does leaving it public"):
    served: world =>
      for
        owner  <- world.signUp("owner")
        club   <- world.groups.create(owner.id, GroupDetails("Club"))
        _      <- world.service.setPublic(owner, club.id, public = true)
        opened <- world.reported
        _      <- world.service.setPublic(owner, club.id, public = false)
        closed <- world.reported
      yield
        assertEquals(
          opened,
          List(Affected.Groups(Audience.Everyone, Set(club.id))),
        )
        assertEquals(
          closed,
          List(Affected.Groups(Audience.Everyone, Set(club.id))),
        )

  test("deleting a group concerns whoever was in it and its subgroups"):
    served: world =>
      for
        owner   <- world.signUp("owner")
        member  <- world.signUp("member")
        company <- world.groups.create(owner.id, GroupDetails("Company"))
        team    <- world
          .groups
          .create(
            owner.id,
            GroupDetails("Team", Some(company.id)),
          )
        _    <- world.join(owner, team.id, member)
        _    <- world.reported
        _    <- world.service.delete(owner, company.id)
        told <- world.reported
      yield assertEquals(
        told,
        List(Affected.Groups(
          Audience.People(Set(owner.id, member.id)),
          Set(team.id, company.id),
        )),
      )

  test("a new password concerns the account, and a refused change nobody"):
    served: world =>
      for
        user     <- world.signUp("user")
        stranger <- world.signUp("stranger")
        club     <- world.groups.create(user.id, GroupDetails("Club"))
        _        <- world.reported
        _        <- world
          .auth
          .changePassword(
            user,
            PasswordChange("hunter2222", "hunter3333"),
          )
        told    <- world.reported
        refused <- world.service.update(stranger, club.id, GroupDetails("Mine"))
        none    <- world.reported
      yield
        assertEquals(told, List(Affected.Account(user.id)))
        assert(
          refused.isLeft,
          "a stranger renamed someone else's group",
        )
        assertEquals(none, List.empty)

object AffectedSuite:

  private val book = Resource("book", 1)

  private def invitations(told: List[Affected]): List[Affected.Invited] = told
    .collect { case invited: Affected.Invited => invited }

  private final class World
    (
      val db: TestDb,
      val auth: AuthService,
      val service: GroupService,
      val linking: LinkService,
      told: Ref[IO, List[Affected]],
    ):

    val groups = GroupStore(TestDb.tables, db)
    val links  = LinkStore(TestDb.tables)

    /** Drains what has been reported, oldest first. */
    def reported: IO[List[Affected]] = told.getAndSet(Nil).map(_.reverse)

    def signUp(name: String): IO[User] = Fixtures.signUp(auth, name).map(_._1)

    /** Enrols a member through the store, which reports nothing. */
    def join(owner: User, group: Long, member: User): IO[Unit] =
      enrol(groups, owner, group, member)

  private def served(check: World => IO[Unit]): IO[Unit] = TestDb
    .open("affected")
    .use: db =>
      for
        told <- Ref.of[IO, List[Affected]](Nil)
        tell   = (affected: Affected) => told.update(affected :: _)
        groups = GroupStore(TestDb.tables, db)
        _ <- check(World(
          db,
          AuthService(
            UserStore(TestDb.tables, db),
            cheap,
            affected = tell,
          ),
          GroupService(groups, affected = tell),
          LinkService(
            LinkStore(TestDb.tables),
            groups,
            GrantStore(TestDb.tables, db),
            db,
            resource => DBIO.successful(Option.when(resource == book)("Dune")),
            affected = tell,
          ),
          told,
        ))
      yield ()
