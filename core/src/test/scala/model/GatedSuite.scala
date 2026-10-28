package com.alecdorrington.hecate
package model

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

class GatedSuite extends FunSuite:

  test("each state is sent and read back under its spelled name, and no other"):
    val absent: Gated[Int]          = Gated.Absent
    val withheld: Gated[Int]        = Gated.Withheld
    val nothing: Gated[Option[Int]] = Gated.Shown(None)
    List[Gated[Int]](Gated.Shown(3), absent, withheld).foreach(part =>
      assertEquals(
        part.asJson.as[Gated[Int]],
        Right(part),
      ),
    )
    assertEquals(
      absent.asJson,
      Json.obj("state" -> Json.fromString("absent")),
    )
    assertEquals(
      withheld.asJson,
      Json.obj("state" -> Json.fromString("withheld")),
    )
    // A part shown as nothing stays shown rather than becoming absent.
    assertEquals(
      nothing.asJson,
      Json.obj(
        "state" -> Json.fromString("shown"),
        "value" -> Json.Null,
      ),
    )
    assertEquals(
      nothing.asJson.as[Gated[Option[Int]]],
      Right(nothing),
    )
    val unknown = Json.obj("state" -> Json.fromString("hidden"))
    assert(unknown.as[Gated[Int]].isLeft)

  test("blank text is absent, and other text is shown"):
    assertEquals(Gated.fromText("  "), Gated.Absent)
    assertEquals(
      Gated.fromText("Sort by author."),
      Gated.Shown("Sort by author."),
    )
