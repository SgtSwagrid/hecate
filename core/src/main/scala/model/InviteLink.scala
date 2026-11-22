package com.alecdorrington.hecate
package model

import io.circe.{Codec, Decoder, DecodingFailure, Encoder, Json, JsonObject}
import io.circe.syntax.*

/**
  * What following an invite link gives: membership of a group, or access to a
  * resource. Stored and sent as its [[kind]] and what it names, never by its
  * case name.
  *
  * @param kind
  *   The name this kind of target is stored and sent as (e.g. `group`).
  */
enum LinkTarget(val kind: String):

  /**
    * Membership of a group, as though its owner had invited whoever follows the
    * link and they had accepted.
    *
    * @param groupId
    *   The identifier of the group.
    */
  case Joining(groupId: Long) extends LinkTarget(LinkTarget.groupKind)

  /**
    * Access to a resource, granted to whoever follows the link.
    *
    * @param resource
    *   The resource.
    *
    * @param access
    *   The level of access granted. Never [[Access.Own]].
    */
  case Sharing(resource: Resource, access: Access)
    extends LinkTarget(LinkTarget.resourceKind)

object LinkTarget:

  /** The stored name of the kind of a [[LinkTarget.Joining]]. Never change it. */
  val groupKind: String = "group"

  /** The stored name of the kind of a [[LinkTarget.Sharing]]. Never change it. */
  val resourceKind: String = "resource"

  given Codec.AsObject[LinkTarget] = Codec
    .AsObject
    .from(
      Decoder.instance(cursor =>
        cursor
          .get[String]("kind")
          .flatMap:
            case `groupKind`    => cursor.get[Long]("groupId").map(Joining(_))
            case `resourceKind` =>
              for
                resource <- cursor.get[Resource]("resource")
                access   <- cursor.get[Access]("access")
              yield Sharing(resource, access)
            case other => Left(DecodingFailure(
                s"Unknown kind of link target `$other`.",
                cursor.history,
              )),
      ),
      Encoder
        .AsObject
        .instance(target =>
          JsonObject.fromIterable(
            ("kind" -> target.kind.asJson) +: fields(target),
          ),
        ),
    )

  private def fields(target: LinkTarget): List[(String, Json)] = target match
    case Joining(groupId)          => List("groupId" -> groupId.asJson)
    case Sharing(resource, access) => List(
        "resource" -> resource.asJson,
        "access"   -> access.asJson,
      )

/**
  * The random code an invite link is known by: six hexadecimal digits, read in
  * any case. Each holds at least one of `0` to `9`, so that it never spells a
  * word (such as `decade`) or collides with a host's own paths.
  */
object InviteCode:

  /** The number of characters in every code. */
  val length: Int = 6

  /** The characters a code may hold, as stored. */
  val alphabet: String = ('0' to '9').mkString + ('a' to 'f').mkString

  /**
    * Parses a code from text in any case, lowering letters without regard to
    * locale.
    *
    * @param text
    *   The text to read a code from.
    *
    * @return
    *   A code in lower case, or `None` if the text is not one.
    */
  def parse(text: String): Option[String] = Some(text.map(lower)).filter(valid)

  /**
    * Checks whether text in lower case is a code.
    *
    * @param code
    *   The text to check, in lower case.
    *
    * @return
    *   Whether the text is a code.
    */
  def valid(code: String): Boolean = code.length == length &&
    code.forall(alphabet.contains) && code.exists(char => char <= '9')

  private def lower(char: Char): Char =
    if char >= 'A' && char <= 'Z' then (char + ('a' - 'A')).toChar else char

/**
  * A resource's invite link, as its owners see it.
  *
  * @param code
  *   The code the link is known by (see [[InviteCode]]).
  *
  * @param access
  *   The level of access following the link grants.
  */
final case class InviteLink(code: String, access: Access) derives Codec.AsObject

/**
  * Where an invite link leads, shown to whoever opens it before they choose
  * whether to follow it.
  *
  * @param target
  *   The target the link leads to.
  *
  * @param name
  *   The name of the group or resource it leads to.
  *
  * @param inviter
  *   The user who made the link.
  *
  * @param already
  *   Whether the user opening the link already has everything it gives.
  */
final case class LinkPreview
  (
    target: LinkTarget,
    name: String,
    inviter: User,
    already: Boolean,
  )
  derives Codec.AsObject
