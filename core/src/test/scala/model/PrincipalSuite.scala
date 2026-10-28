package com.alecdorrington.hecate
package model

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

class PrincipalSuite extends FunSuite:

  test("a principal is sent as its spelled kind, and read back as any case"):
    List[(Principal, String)](
      Principal.Person(7) -> "person",
      Principal.Group(7)  -> "group",
      Principal.System    -> "system",
    ).foreach: (principal, kind) =>
      assertEquals(
        principal.asJson.hcursor.downField("kind").as[String],
        Right(kind),
      )
      assertEquals(
        principal.asJson.as[Principal],
        Right(principal),
      )

  test("every kind is restored from what it is stored as, and nothing else"):
    List(
      Principal.Person(5),
      Principal.Group(5),
      Principal.System,
    ).foreach(principal =>
      assertEquals(
        Principal.of(principal.kind, principal.id),
        Some(principal),
      ),
    )
    // The system is one principal, never another identifier.
    assertEquals(Principal.System.id, 0L)
    assertEquals(Principal.of("system", 1), None)
    // A kind this version does not know is refused rather than guessed at.
    assertEquals(Principal.of("robot", 1), None)
    assert(
      decode[Principal]("""{"kind":"robot","id":1}""").isLeft,
      "an unknown kind decoded to something",
    )
