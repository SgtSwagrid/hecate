package com.alecdorrington.hecate
package server

import Fixtures.cheap
import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.api.Protocol
import com.alecdorrington.hecate.model.{
  AuthRefusal, Credentials, PasswordChange, Principal, User,
}
import munit.CatsEffectSuite
import slick.dbio.DBIO

class GuestSuite extends CatsEffectSuite:

  import GuestSuite.*

  test("a guest is made under the name they gave, numbered where it is taken"):
    guests(): world =>
      for
        made  <- world.arrive("  Reader  ")
        found <- world.auth.current(Some(made._2.token))
        _     <- world.auth.register(Credentials("Sam", "hunter2222"))
        first <- world.arrive("Sam")
        next  <- world.arrive("Sam")
        other <- world.arrive("Samantha")
        lower <- world.arrive("sam")
      yield
        assertEquals(
          (made._1.username, made._1.guest),
          ("Reader", true),
        )
        assertEquals(found, Right(Some(made._1)))
        assertEquals(first._1.username, "Sam 2")
        assertEquals(lower._1.username, "sam 4")
        assertEquals(next._1.username, "Sam 3")
        assertEquals(other._1.username, "Samantha")
        assertEquals(
          UserStore.numbered("Sam", Set("sam", "sam 2", "sam 4")),
          "Sam 3",
        )

  test("guests of one name made at once are each numbered apart"):
    guests(): world =>
      (1 to 5)
        .toList
        .parTraverse(_ => world.arrive("Twin"))
        .map(made =>
          assertEquals(
            made.map(_._1.username).toSet,
            Set(
              "Twin",
              "Twin 2",
              "Twin 3",
              "Twin 4",
              "Twin 5",
            ),
          ),
        )

  test("nothing is made where what the guest came for refuses"):
    guests(): world =>
      for
        refused <- world
          .auth
          .createGuest("Reader")(_ =>
            DBIO.failed(AuthProblem(AuthRefusal.LinkMissing)),
          )
        found <- world.users.findByUsername("Reader")
      yield
        assertEquals(
          refused.map(_._1),
          Left(AuthRefusal.LinkMissing),
        )
        assertEquals(found, None)

  test("a guest needs a name, not too long, and a policy that lets them in"):
    guests(): world =>
      val longest = "a" * Protocol.maxGuestNameLength
      for
        blank <- world.auth.createGuest("   ")(_ => DBIO.unit)
        long  <- world.auth.createGuest(longest + "a")(_ => DBIO.unit)
        fits  <- world.auth.createGuest(longest)(_ => DBIO.unit)
        shut  <-
          AuthService(world.users, cheap).createGuest("Reader")(_ => DBIO.unit)
      yield
        assertEquals(
          blank.map(_._1),
          Left(AuthRefusal.GuestNameEmpty),
        )
        assertEquals(
          long.map(_._1),
          Left(AuthRefusal.TooLong(Protocol.maxGuestNameLength)),
        )
        assertEquals(
          fits.map(_._1.username),
          Right(longest),
        )
        assertEquals(
          shut.map(_._1),
          Left(AuthRefusal.NoGuests),
        )
        assert(world.auth.rules.guests)
        assert(!AuthService(world.users, cheap).rules.guests)

  test("nobody signs in to a guest's account, nor changes its password"):
    guests(): world =>
      for
        made    <- world.arrive("Reader")
        signIn  <- world.auth.signIn(Credentials("Reader", "hunter2222"))
        changed <- world
          .auth
          .changePassword(
            made._1,
            PasswordChange("", "hunter2222"),
          )
      yield
        assertEquals(
          signIn.map(_._1),
          Left(AuthRefusal.CredentialsIncorrect),
        )
        assertEquals(
          changed,
          Left(AuthRefusal.PasswordIncorrect),
        )

  test("a guest claims their account, and signs in to it from then on"):
    guests(): world =>
      for
        made    <- world.arrive("Reader")
        claimed <- world
          .auth
          .claim(
            made._1,
            Credentials(" reader ", "hunter2222"),
          )
        (user, session) = claimed.toOption.get
        old    <- world.auth.current(Some(made._2.token))
        fresh  <- world.auth.current(Some(session.token))
        signIn <- world.auth.signIn(Credentials("reader", "hunter2222"))
      yield
        assertEquals(user, User(made._1.id, "reader"))
        assertEquals(old, Right(None))
        assertEquals(fresh, Right(Some(user)))
        assertEquals(signIn.map(_._1), Right(user))

  test("only a guest's account is claimed, under no name another has taken"):
    guests(): world =>
      for
        alice <- world.auth.register(Credentials("alice", "hunter2222"))
        again <- world
          .auth
          .claim(
            alice.toOption.get._1,
            Credentials("alicia", "hunter2222"),
          )
        made  <- world.arrive("Reader")
        taken <- world
          .auth
          .claim(
            made._1,
            Credentials("ALICE", "hunter2222"),
          )
        still <- world.auth.current(Some(made._2.token))
        // The name they gave is theirs to keep.
        kept <- world
          .auth
          .claim(
            made._1,
            Credentials("Reader", "hunter2222"),
          )
      yield
        assertEquals(
          again.map(_._1),
          Left(AuthRefusal.NotGuest),
        )
        assertEquals(
          taken.map(_._1),
          Left(AuthRefusal.UsernameTaken),
        )
        assertEquals(still, Right(Some(made._1)))
        assertEquals(
          kept.map(_._1),
          Right(User(made._1.id, "Reader")),
        )

  test("a guest deletes their account with no password, and nobody else can"):
    guests(): world =>
      for
        made    <- world.arrive("Reader")
        gone    <- world.auth.deleteAccount(made._1, "")
        found   <- world.users.find(made._1.id)
        alice   <- world.auth.register(Credentials("alice", "hunter2222"))
        refused <- world.auth.deleteAccount(alice.toOption.get._1, "")
      yield
        assertEquals(gone, Right(SessionCookie.closed))
        assertEquals(found, None)
        assertEquals(
          refused,
          Left(AuthRefusal.PasswordIncorrect),
        )

object GuestSuite:

  private final class World
    (
      val users: UserStore,
      val auth: AuthService,
    ):

    def arrive(name: String): IO[(User, SessionCookie)] = auth
      .createGuest(name)(_ => DBIO.unit)
      .map(made =>
        val (guest, _, session) = made.toOption.get
        (guest, session),
      )

  private def guests()(check: World => IO[Unit]): IO[Unit] = TestDb
    .open("guests")
    .use: db =>
      val users    = UserStore(TestDb.tables, db)
      val groups   = GroupStore(TestDb.tables, db)
      val grants   = GrantStore(TestDb.tables, db)
      val accounts = AccountStore(
        TestDb.tables,
        db,
        users,
        groups,
        grants,
        (_: Seq[Principal]) => DBIO.unit,
      )
      check(World(
        users,
        AuthService(
          users,
          cheap.copy(guests = true),
          accounts = Some(accounts),
        ),
      ))
