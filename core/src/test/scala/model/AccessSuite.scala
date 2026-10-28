package com.alecdorrington.hecate
package model

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

class AccessSuite extends FunSuite:

  test("each level includes itself and every level below it, in order"):
    assert(Access.Own.includes(Access.View))
    assert(Access.Edit.includes(Access.Edit))
    assert(!Access.View.includes(Access.Edit))
    assertEquals(
      List(Access.Own, Access.View, Access.Edit).sorted,
      List(Access.View, Access.Edit, Access.Own),
    )

  test("levels go by stable codes, and an unknown code is refused"):
    assertEquals(
      Access.values.map(_.code).toList,
      List("view", "edit", "own"),
    )
    Access
      .values
      .foreach(level =>
        assertEquals(
          Access.fromCode(level.code),
          Some(level),
        ),
      )
    assertEquals(
      Access.Edit.asJson,
      Json.fromString("edit"),
    )
    assert(Json.fromString("admin").as[Access].isLeft)
