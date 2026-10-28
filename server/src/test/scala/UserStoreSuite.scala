package com.alecdorrington.hecate
package server

import Fixtures.register
import cats.effect.IO
import munit.CatsEffectSuite
import slick.jdbc.H2Profile.api.*

class UserStoreSuite extends CatsEffectSuite:

  private def withStore(name: String)(check: UserStore => IO[Unit]): IO[Unit] =
    TestDb.users(s"users-$name").use(check)

  test("a username is registered as chosen, then taken in any letter case"):
    withStore("register"): users =>
      for
        user    <- users.register("Alice", "hash")
        same    <- users.register("Alice", "other")
        again   <- users.register("aLICE", "other")
        found   <- users.findByUsername("ALICE")
        unknown <- users.findByUsername("nobody")
      yield
        assertEquals(user.map(_.username), Some("Alice"))
        assertEquals((same, again), (None, None))
        assertEquals(found.map(_.toUser), user)
        assertEquals(unknown, None)

  test("a session is stored as the hash of its token, never the token"):
    TestDb
      .open("users-session-hash")
      .use: db =>
        val users = UserStore(TestDb.tables, db)
        for
          user   <- register(users, "alice")
          _      <- users.openSession("secret", user.id, UserStoreSuite.soon)
          stored <- db.run(TestDb.tables.sessions.result)
          found  <- users.sessionUser("secret")
        yield
          assertEquals(
            stored.map(_.tokenHash),
            Seq(Digest.of("secret")),
          )
          assertEquals(found, Some(user))

  test("a closed, expired or unknown session resolves to nobody"):
    withStore("closed"): users =>
      for
        user    <- register(users, "alice")
        _       <- users.openSession("token", user.id, UserStoreSuite.soon)
        _       <- users.openSession("stale", user.id, UserStoreSuite.past)
        _       <- users.closeSession("token")
        closed  <- users.sessionUser("token")
        expired <- users.sessionUser("stale")
        unknown <- users.sessionUser("bogus")
      yield assertEquals(
        (closed, expired, unknown),
        (None, None, None),
      )

  test("purging clears out the expired sessions and keeps the live ones"):
    withStore("sweep"): users =>
      for
        user <- register(users, "alice")
        _    <- users.openSession("stale", user.id, UserStoreSuite.past)
        _    <- users.openSession("fresh", user.id, UserStoreSuite.soon)
        _    <- users.purgeExpired
        // The stale row is gone, so a token that collides with it cannot be
        // rejected by the primary key.
        _     <- users.openSession("stale", user.id, UserStoreSuite.soon)
        held  <- users.sessionUser("stale")
        fresh <- users.sessionUser("fresh")
      yield assertEquals((held, fresh), (Some(user), Some(user)))

  test("rehashing a password leaves every session where it was"):
    withStore("rehash"): users =>
      for
        user <- users.register("alice", "old").map(_.get)
        _    <- users.openSession("laptop", user.id, UserStoreSuite.soon)
        _    <- users.rehash(user.id, "stronger")
        row  <- users.find(user.id)
        open <- users.sessionUser("laptop")
      yield
        assertEquals(
          row.flatMap(_.passwordHash),
          Some("stronger"),
        )
        assertEquals(open, Some(user))

  test("a recovery code regains the account once, and no other code does"):
    withStore("recover"): users =>
      def recover
        (
          username: String,
          code: String,
          password: String = "new",
        ) = users.recover(
        username,
        code,
        password,
        s"$username-$code-$password",
        UserStoreSuite.soon,
      )
      for
        user     <- users.register("alice", "old").map(_.get)
        _        <- users.replaceRecoveryCodes(user.id, List("old-1", "old-2"))
        _        <- users.replaceRecoveryCodes(user.id, List("code-hash"))
        replaced <- recover("alice", "old-1")
        wrong    <- recover("alice", "not-it")
        unknown  <- recover("nobody", "code-hash")
        kept     <- users.find(user.id)
        before   <- users.recoveryCodesLeft(user.id)
        first    <- recover("alice", "code-hash")
        // A replay sets no password of its own.
        again <- recover("alice", "code-hash", "newer")
        row   <- users.find(user.id)
        left  <- users.recoveryCodesLeft(user.id)
      yield
        assertEquals(
          (replaced, wrong, unknown),
          (None, None, None),
        )
        assertEquals(
          kept.flatMap(_.passwordHash),
          Some("old"),
        )
        assertEquals(before, 1)
        assertEquals(first, Some(user))
        assertEquals(again, None)
        assertEquals(
          row.flatMap(_.passwordHash),
          Some("new"),
        )
        assertEquals(left, 0)

  test("users are looked up by identifier, with unknown identifiers left out"):
    withStore("named"): users =>
      for
        alice <- register(users, "alice")
        bob   <- register(users, "bob")
        found <- users.byIds(List(alice.id, bob.id, alice.id, 9999L))
        none  <- users.byIds(List.empty)
      yield
        assertEquals(
          found,
          Map(alice.id -> alice, bob.id -> bob),
        )
        assertEquals(none, Map.empty)

object UserStoreSuite:

  private val soon = System.currentTimeMillis + 60000

  private val past = System.currentTimeMillis - 1
