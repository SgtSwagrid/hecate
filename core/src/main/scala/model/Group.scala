package com.alecdorrington.hecate
package model

import io.circe.{Codec, Decoder, Encoder, JsonObject}
import io.circe.syntax.*

/**
  * A named group of users, which may nest inside another group to any depth.
  * Who manages it is decided by grants over it as a resource
  * ([[Resource.group]]), its creator first holding [[Access.Own]].
  *
  * @param id
  *   The identifier of the group, assigned by the store.
  *
  * @param name
  *   The display name of the group.
  *
  * @param parentId
  *   The identifier of the group this one nests inside, or `None` for a
  *   top-level group. Also `None` for a reader who cannot see the parent.
  *
  * @param public
  *   Whether anyone may find the group and ask to join it, not only the members
  *   of the groups enclosing it.
  */
final case class Group
  (
    id: Long,
    name: String,
    parentId: Option[Long] = None,
    public: Boolean = false,
  )
  derives Codec.AsObject

/**
  * The content of a new or updated group.
  *
  * @param name
  *   The display name of the group.
  *
  * @param parentId
  *   The identifier of the group it nests inside, or `None` for a top-level
  *   group.
  */
final case class GroupDetails
  (
    name: String,
    parentId: Option[Long] = None,
  )
  derives Codec.AsObject

/**
  * A principal holding access, as it is shown to someone who may not be able to
  * address it.
  *
  * @param principal
  *   The user, group or system.
  *
  * @param name
  *   The user's username or the group's name, or `None` for the system, which
  *   the host names.
  */
final case class Holder
  (
    principal: Principal,
    name: Option[String],
  )
  derives Codec.AsObject

/**
  * A group as someone who manages it sees it: anyone holding at least
  * [[Access.View]] over it. Nesting is given by [[Group.parentId]] alone.
  *
  * @param group
  *   The group.
  *
  * @param access
  *   The access the reader holds over the group: [[Access.View]] to see who is
  *   in it, [[Access.Edit]] to change that and the group itself, and
  *   [[Access.Own]] to delete it and choose who manages it.
  *
  * @param members
  *   The users directly in the group, sorted by username. Users only in groups
  *   nested inside it are not included.
  *
  * @param invitees
  *   The users invited who have not yet accepted, sorted by username. They are
  *   not members.
  *
  * @param applicants
  *   The users who have asked to join and await an answer, sorted by username.
  *   They are not members.
  *
  * @param inviteCode
  *   The code of the group's invite link, or `None` if it has none or the
  *   reader may not change who is in the group.
  *
  * @param memberGroups
  *   The groups that are members of it, by name, whose members belong to it
  *   through them, each with its parent withheld. Nested groups are not among
  *   them.
  *
  * @param invitedGroups
  *   The groups invited to be members of it that have not yet accepted, by
  *   name, each with its parent withheld.
  *
  * @param memberOf
  *   The groups it is a member of, by name, each with its parent withheld,
  *   which its members belong to through it.
  */
final case class ManagedGroup
  (
    group: Group,
    access: Access,
    members: List[User],
    invitees: List[User] = List.empty,
    applicants: List[User] = List.empty,
    inviteCode: Option[String] = None,
    memberGroups: List[Group] = List.empty,
    invitedGroups: List[Group] = List.empty,
    memberOf: List[Group] = List.empty,
  )
  derives Codec.AsObject

/**
  * A group as one of its members sees it.
  *
  * @param group
  *   The group.
  *
  * @param owners
  *   Whoever holds [[Access.Own]] over the group.
  *
  * @param members
  *   The users directly in the group, the reader among them, sorted by
  *   username.
  *
  * @param enclosing
  *   The groups enclosing it, outermost first, which the reader belongs to
  *   through it, without their members.
  *
  * @param memberOf
  *   The groups it is a member of, by name, which the reader belongs to through
  *   it, without their members.
  */
final case class Membership
  (
    group: Group,
    owners: List[Holder],
    members: List[User],
    enclosing: List[Group] = List.empty,
    memberOf: List[Group] = List.empty,
  )
  derives Codec.AsObject

/**
  * A pending invitation to join a group, as the invited user sees it.
  *
  * @param id
  *   The identifier of the invitation, assigned by the store.
  *
  * @param group
  *   The group invited to, with its parent withheld.
  *
  * @param inviter
  *   The user who sent the invitation, or `None` if their account is gone.
  */
final case class Invitation
  (
    id: Long,
    group: Group,
    inviter: Option[User],
  )
  derives Codec.AsObject

/**
  * A pending invitation for a group to become a member of another, as those who
  * may change the invited group see it. Accepting makes the invited group's
  * members, and those of every group within it, members of the other through
  * it.
  *
  * @param id
  *   The identifier of the invitation, assigned by the store.
  *
  * @param group
  *   The group invited to, with its parent withheld.
  *
  * @param invitee
  *   The group invited, with its parent withheld.
  *
  * @param inviter
  *   The user who sent the invitation, or `None` if their account is gone.
  */
final case class GroupInvitation
  (
    id: Long,
    group: Group,
    invitee: Group,
    inviter: Option[User],
  )
  derives Codec.AsObject

/**
  * A group the signed-in user may ask to join: a public group, or one nested
  * inside a group they are a member of.
  *
  * @param group
  *   The group, with its parent withheld unless the user can see it.
  *
  * @param owners
  *   Whoever holds [[Access.Own]] over the group.
  *
  * @param requested
  *   Whether the user has already asked to join.
  */
final case class JoinableGroup
  (
    group: Group,
    owners: List[Holder],
    requested: Boolean,
  )
  derives Codec.AsObject

/** Whom to invite to a group: a person, or another group, as a member. */
enum Invitee(val kind: String):

  /**
    * A person, named by their username.
    *
    * @param username
    *   The username of the person to invite.
    */
  case Person(username: String) extends Invitee(Invitee.personKind)

  /**
    * A group, to become a member of the group invited to.
    *
    * @param id
    *   The identifier of the group to invite.
    */
  case Group(id: Long) extends Invitee(Invitee.groupKind)

object Invitee:

  /** The sent name of the kind of an [[Invitee.Person]]. */
  val personKind: String = "person"

  /** The sent name of the kind of an [[Invitee.Group]]. */
  val groupKind: String = "group"

  given Codec.AsObject[Invitee] = Codec
    .AsObject
    .from(
      Decoder[String]
        .at("kind")
        .flatMap:
          case `personKind` => Decoder[String].at("username").map(Person(_))
          case `groupKind`  => Decoder[Long].at("id").map(Group(_))
          case other        =>
            Decoder.failedWithMessage(s"Unknown kind of invitee `$other`."),
      Encoder
        .AsObject
        .instance:
          case Person(username) => JsonObject(
              "kind"     -> personKind.asJson,
              "username" -> username.asJson,
            )
          case Group(id) => JsonObject(
              "kind" -> groupKind.asJson,
              "id"   -> id.asJson,
            ),
    )

/** What inviting someone or a group to a group came to. */
enum InviteStatus(val code: String):

  /** An invitation awaits their answer, sent now or before. */
  case Invited extends InviteStatus("invited")

  /**
    * They joined at once: the inviter may agree for them, or they had asked to
    * join.
    */
  case Joined extends InviteStatus("joined")

  /** They were a member already, and nothing changed. */
  case Member extends InviteStatus("member")

object InviteStatus:

  /**
    * Finds the status a code names.
    *
    * @param code
    *   The status's code.
    *
    * @return
    *   The status, or `None` for an unknown code.
    */
  def fromCode(code: String): Option[InviteStatus] = values.find(_.code == code)

  given Codec[InviteStatus] = Codec.from(
    Decoder
      .decodeString
      .emap(code => fromCode(code).toRight(s"Unknown invite status `$code`.")),
    Encoder.encodeString.contramap(_.code),
  )

/**
  * What inviting someone or a group to a group came to.
  *
  * @param invitee
  *   The person or group invited, named as stored.
  *
  * @param status
  *   Whether they were invited, joined at once, or were a member already.
  */
final case class InviteResult(invitee: Holder, status: InviteStatus)
  derives Codec.AsObject
