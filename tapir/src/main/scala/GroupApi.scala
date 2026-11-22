package com.alecdorrington.hecate
package tapir

import com.alecdorrington.hecate.model.{
  Group, GroupDetails, GroupInvitation, Invitation, Invitee, InviteResult,
  JoinableGroup, ManagedGroup, Membership, Principal,
}
import com.alecdorrington.hecate.tapir.Schemas.given
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Descriptions of the endpoints for user groups, which nest through
  * [[Group.parentId]], and whose managers are shared through [[SharingApi]] as
  * any resource's holders are. Nobody joins a group without consenting: anyone
  * who may change it joins at once, and anyone else by accepting an invitation,
  * having a request admitted, or following an invite link.
  */
object GroupApi:

  private def group: EndpointInput[Long] = "api" / "groups" /
    path[Long]("group")

  /**
    * An endpoint that lists the groups the signed-in user manages, holding at
    * least view access over them.
    */
  val list: AuthApi.Secured[Unit, List[ManagedGroup]] = AuthApi
    .secured
    .get
    .in("api" / "groups")
    .out(jsonBody[List[ManagedGroup]])

  /**
    * An endpoint that lists the principals the signed-in user acts as:
    * themselves, every group they belong to, and the system if they act for it.
    */
  val principals: AuthApi.Secured[Unit, List[Principal]] = AuthApi
    .secured
    .get
    .in("api" / "principals")
    .out(jsonBody[List[Principal]])

  /**
    * An endpoint that lists the groups the signed-in user is a member of, each
    * with its owners and members.
    */
  val mine: AuthApi.Secured[Unit, List[Membership]] = AuthApi
    .secured
    .get
    .in("api" / "groups" / "mine")
    .out(jsonBody[List[Membership]])

  /** An endpoint that withdraws the signed-in user from a group. */
  val leave: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .delete
    .in(group / "membership")

  /**
    * An endpoint that makes the signed-in user a member of a group they may
    * change. Joining a group they are in changes nothing.
    */
  val join: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .put
    .in(group / "membership")

  /**
    * An endpoint that lists the groups the signed-in user may ask to join:
    * public groups and groups nested inside one they are a member of, but none
    * they manage, belong to or are invited to. Groups they have asked to join
    * are always listed, so that they can withdraw the request.
    */
  val joinable: AuthApi.Secured[Unit, List[JoinableGroup]] = AuthApi
    .secured
    .get
    .in("api" / "groups" / "joinable")
    .out(jsonBody[List[JoinableGroup]])

  /**
    * An endpoint that asks to join a group the signed-in user may see. Asking
    * twice changes nothing; asking to join a group they are invited to accepts
    * the invitation, and one they may change joins it.
    */
  val request: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .put
    .in(group / "request")

  /**
    * An endpoint that withdraws the signed-in user's request to join a group.
    * Withdrawing a request never made changes nothing.
    */
  val withdraw: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .delete
    .in(group / "request")

  /**
    * An endpoint that creates a group owned by the signed-in user, returning it
    * with its identifier.
    */
  val create: AuthApi.Secured[GroupDetails, Group] = AuthApi
    .secured
    .post
    .in("api" / "groups")
    .in(jsonBody[GroupDetails])
    .out(jsonBody[Group])

  /**
    * An endpoint that renames or moves a group. A move nesting a group inside
    * itself is refused.
    */
  val update: AuthApi.Secured[(Long, GroupDetails), Unit] = AuthApi
    .secured
    .put
    .in(group)
    .in(jsonBody[GroupDetails])

  /**
    * An endpoint that deletes a group and every group nested inside it that the
    * signed-in user owns too. Any other group nested inside it takes its place.
    */
  val delete: AuthApi.Secured[Long, Unit] = AuthApi.secured.delete.in(group)

  /**
    * An endpoint that makes a group public, so that anyone may find it and ask
    * to join, or private again.
    */
  val setPublic: AuthApi.Secured[(Long, Boolean), Unit] = AuthApi
    .secured
    .put
    .in(group / "public")
    .in(jsonBody[Boolean])

  /**
    * An endpoint that gives a group an invite link unless it has one, returning
    * the link's code either way (see [[LinkApi]]).
    */
  val link: AuthApi.Secured[Long, String] = AuthApi
    .secured
    .put
    .in(group / "invite-link")
    .out(jsonBody[String])

  /**
    * An endpoint that replaces a group's invite link with a new one, returning
    * its code. The old link stops working.
    */
  val relink: AuthApi.Secured[Long, String] = AuthApi
    .secured
    .post
    .in(group / "invite-link")
    .out(jsonBody[String])

  /** An endpoint that turns off a group's invite link. */
  val unlink: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .delete
    .in(group / "invite-link")

  /**
    * An endpoint that invites a person, by username, or a group, as a member,
    * to a group, answering whom it invited and what came of it. Inviting a
    * member or an invitee changes nothing; inviting an applicant admits them,
    * and inviting oneself, or anyone or any group the inviter may agree for,
    * joins.
    */
  val invite: AuthApi.Secured[(Long, Invitee), InviteResult] = AuthApi
    .secured
    .post
    .in(group / "invitations")
    .in(jsonBody[Invitee])
    .out(jsonBody[InviteResult])

  /**
    * An endpoint that takes a group out of another, or withdraws its invitation
    * to it, for whoever may change either.
    */
  val removeGroup: AuthApi.Secured[(Long, Long), Unit] = AuthApi
    .secured
    .delete
    .in(group / "member-groups" / path[Long]("member"))

  /**
    * An endpoint that lists the pending invitations for groups the signed-in
    * user may change to become members of others.
    */
  val groupInvitations: AuthApi.Secured[Unit, List[GroupInvitation]] = AuthApi
    .secured
    .get
    .in("api" / "group-invitations")
    .out(jsonBody[List[GroupInvitation]])

  /**
    * An endpoint that accepts an invitation for a group the signed-in user may
    * change, making it a member of the other.
    */
  val acceptGroup: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .post
    .in("api" / "group-invitations" / path[Long]("invitation") / "accept")

  /**
    * An endpoint that declines an invitation for a group the signed-in user may
    * change, deleting it.
    */
  val declineGroup: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .post
    .in("api" / "group-invitations" / path[Long]("invitation") / "decline")

  /**
    * An endpoint that admits to a group a user who has asked to join it.
    * Admitting a member changes nothing.
    */
  val admit: AuthApi.Secured[(Long, Long), Unit] = AuthApi
    .secured
    .put
    .in(group / "members" / path[Long]("member"))

  /**
    * An endpoint that removes a user from a group: a member, an invitee whose
    * invitation is cancelled, or an applicant whose request is declined.
    */
  val remove: AuthApi.Secured[(Long, Long), Unit] = AuthApi
    .secured
    .delete
    .in(group / "members" / path[Long]("member"))

  /** An endpoint that lists the pending invitations sent to the signed-in user. */
  val invitations: AuthApi.Secured[Unit, List[Invitation]] = AuthApi
    .secured
    .get
    .in("api" / "invitations")
    .out(jsonBody[List[Invitation]])

  /**
    * An endpoint that accepts one of the signed-in user's invitations, joining
    * its group.
    */
  val accept: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .post
    .in("api" / "invitations" / path[Long]("invitation") / "accept")

  /**
    * An endpoint that declines one of the signed-in user's invitations,
    * deleting it.
    */
  val decline: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .post
    .in("api" / "invitations" / path[Long]("invitation") / "decline")
