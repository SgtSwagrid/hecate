package com.alecdorrington.hecate
package model

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

class InviteLinkSuite extends FunSuite:

  test("a code is six hexadecimal digits, read without regard to case"):
    assertEquals(
      InviteCode.parse("3f9a2c"),
      Some("3f9a2c"),
    )
    assertEquals(
      InviteCode.parse("3F9A2C"),
      Some("3f9a2c"),
    )
    assertEquals(
      InviteCode.parse("000000"),
      Some("000000"),
    )
    List(
      "3f9a2",
      "3f9a2c7",
      "3f9a2g",
      "3f-a2c",
      "3f9a2é",
      "",
      "decade",
      "k3x9q",
      "३f9a2c",
    ).foreach(text => assertEquals(InviteCode.parse(text), None, text))
    // The alphabet is the 16 hexadecimal digits, each once.
    assertEquals(InviteCode.alphabet.length, 16)
    assertEquals(
      InviteCode.alphabet.distinct,
      InviteCode.alphabet,
    )

  test("a target is sent as its kind and what it names, and read back so"):
    assertEquals(
      (LinkTarget.Joining(7): LinkTarget).asJson,
      Json.obj(
        "kind"    -> "group".asJson,
        "groupId" -> 7.asJson,
      ),
    )
    assertEquals(
      (LinkTarget.Sharing(Resource("book", 3), Access.Edit): LinkTarget).asJson,
      Json.obj(
        "kind"     -> "resource".asJson,
        "resource" -> Resource("book", 3).asJson,
        "access"   -> "edit".asJson,
      ),
    )
    List[LinkTarget](
      LinkTarget.Joining(7),
      LinkTarget.Sharing(Resource("book", 3), Access.View),
    ).foreach(target =>
      assertEquals(
        decode[LinkTarget](target.asJson.noSpaces),
        Right(target),
      ),
    )
    assert(decode[LinkTarget]("""{"kind":"planet","id":1}""").isLeft)
