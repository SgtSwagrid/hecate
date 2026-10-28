package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.model.{Access, Group, ManagedGroup, User}
import munit.FunSuite

class GroupsStateSuite extends FunSuite:

  import GroupsStateSuite.*

  test("a list is nested by parent, siblings ordered by name at every depth"):
    val forest = GroupsState.nest(List(
      view(1, "Sales"),
      view(2, "Zebra"),
      view(3, "Apples"),
      view(4, "Yak", parent = 1),
      view(5, "Ants", parent = 1),
      view(6, "Front desk", parent = 5),
    ))
    assertEquals(
      names(forest),
      List("Apples", "Sales", "Zebra"),
    )
    assertEquals(
      names(forest(1).children),
      List("Ants", "Yak"),
    )
    assertEquals(
      names(forest(1).children.head.children),
      List("Front desk"),
    )

  test("every group appears once, its parent missing or in a cycle at the top"):
    // The server never stores a cycle, but the client must not lose a group to
    // one, nor spin on it.
    val groups = List(
      view(1, "Sales", parent = 2),
      view(2, "Retail", parent = 1),
      view(3, "Orphan", parent = 99),
      view(4, "Chess club"),
      view(5, "Front desk", parent = 4),
    )
    val forest = GroupsState.nest(groups)
    val shown  = forest.flatMap(_.flatten).map(_.view.group.id)
    // The groups at the top are ordered by name, a cycle's after the rest.
    assertEquals(
      names(forest),
      List("Chess club", "Orphan", "Retail"),
    )
    assertEquals(
      shown.sorted,
      groups.map(_.group.id).sorted,
    )
    assertEquals(shown.distinct.size, shown.size)

  test("a tree outlines each group with its depth, in depth-first order"):
    val forest = GroupsState.nest(List(
      view(1, "Sales"),
      view(2, "Retail", parent = 1),
      view(3, "Front desk", parent = 2),
      view(4, "Wholesale", parent = 1),
    ))
    assertEquals(
      forest.head.outline().map((group, depth) => (group.group.name, depth)),
      List(
        ("Sales", 0),
        ("Retail", 1),
        ("Front desk", 2),
        ("Wholesale", 1),
      ),
    )

  test("a tree's members are those of the groups beneath it, each once"):
    val forest = GroupsState.nest(List(
      view(
        1,
        "Sales",
        members = List(alice),
        invitees = List(carol),
      ),
      view(
        2,
        "Retail",
        parent = 1,
        members = List(bob, alice),
      ),
      view(3, "Chess club", members = List(carol)),
    ))
    // Invitees are never counted as members.
    assertEquals(
      forest.find(_.view.group.id == 1).get.members.map(_.username),
      List("alice", "bob"),
    )

object GroupsStateSuite:

  private val alice = User(1, "alice")
  private val bob   = User(2, "bob")
  private val carol = User(3, "carol")

  private def view
    (
      id: Long,
      name: String,
      parent: Long = 0,
      members: List[User] = List.empty,
      invitees: List[User] = List.empty,
    )
    : ManagedGroup = ManagedGroup(
    Group(
      id,
      name,
      Option.when(parent != 0)(parent),
    ),
    Access.Own,
    members,
    invitees,
  )

  private def names(forest: List[GroupTree]): List[String] =
    forest.map(_.view.group.name)
