package com.alecdorrington.hecate
package model

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

class InviteeSuite extends FunSuite:

  test("an invitee is sent as its kind beside a username or an id"):
    assertEquals(
      (Invitee.Person("ann"): Invitee).asJson.noSpaces,
      """{"kind":"person","username":"ann"}""",
    )
    assertEquals(
      (Invitee.Group(3): Invitee).asJson.noSpaces,
      """{"kind":"group","id":3}""",
    )
    List[Invitee](
      Invitee.Person("ann"),
      Invitee.Group(3),
    ).foreach(invitee =>
      assertEquals(
        invitee.asJson.as[Invitee],
        Right(invitee),
      ),
    )
    assert(decode[Invitee]("""{"kind":"robot","id":1}""").isLeft)
    assert(decode[Invitee]("""{"kind":"group","username":"ann"}""").isLeft)

  test("an invitation's result is sent with its status's code"):
    val result = InviteResult(
      Holder(Principal.Group(3), Some("Choir")),
      InviteStatus.Joined,
    )
    assertEquals(
      result.asJson.hcursor.downField("status").as[String],
      Right("joined"),
    )
    assertEquals(
      result.asJson.as[InviteResult],
      Right(result),
    )
    assertEquals(InviteStatus.fromCode("lost"), None)
