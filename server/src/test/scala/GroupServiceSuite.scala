package com.alecdorrington.hecate
package server

import Fixtures.register
import cats.effect.IO
import com.alecdorrington.hecate.api.Protocol
import com.alecdorrington.hecate.model.{AuthRefusal, GroupDetails, Invitee}
import munit.CatsEffectSuite

class GroupServiceSuite extends CatsEffectSuite:

  private val overlong = "a" * (Protocol.maxNameLength + 1)

  private def serving
    (check: (GroupService, GroupStore, UserStore) => IO[Unit])
    : IO[Unit] = TestDb
    .groups("group-service")
    .use((groups, users) => check(GroupService(groups), groups, users))

  test("text too long, or a store's refusal, is answered as a value, unstored"):
    serving: (service, groups, users) =>
      for
        owner    <- register(users, "owner")
        created  <- service.create(owner, GroupDetails(overlong))
        stored   <- groups.managed(owner.id)
        team     <- groups.create(owner.id, GroupDetails("Team"))
        invited  <- service.invite(owner, team.id, Invitee(overlong))
        stranger <- register(users, "stranger")
        deleted  <- service.delete(stranger, team.id)
      yield
        assertEquals(
          created.map(_.id),
          Left(AuthRefusal.TooLong(Protocol.maxNameLength)),
        )
        assertEquals(stored, Nil)
        assertEquals(
          invited.map(_.id),
          Left(AuthRefusal.TooLong(Protocol.maxNameLength)),
        )
        assertEquals(deleted, Left(AuthRefusal.GroupMissing))
