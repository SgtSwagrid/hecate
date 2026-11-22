package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.{
  Access, Grant, Group, LinkTarget, Principal, Resource, User,
}
import java.util.Locale
import slick.jdbc.{JdbcCapabilities, JdbcProfile}
import slick.jdbc.meta.MTable

/**
  * A definition of the tables this library stores its data in, against the
  * host's JDBC profile. Create one instance and share it between the stores.
  *
  * No table declares a composite primary key or a foreign key: the stores
  * maintain uniqueness themselves, under the row locks described on
  * [[GroupStore]]. Indexes are created with their table, and so only once (see
  * [[createIfNotExists]]).
  *
  * @param profile
  *   The Slick profile of the host's database.
  *
  * @param prefix
  *   The prefix of every table name. It goes into the SQL identifiers, so it
  *   must be a constant of the host's choosing, never request data.
  */
final class AuthTables
  (
    val profile: JdbcProfile,
    prefix: String = "",
  ):

  import profile.api.*

  /** A table of users, with the columns of [[UserRow]]. */
  final class Users(tag: Tag) extends Table[UserRow](tag, s"${ prefix }users"):

    def id           = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def username     = column[String]("username")
    def usernameKey  = column[String]("username_key", O.Unique)
    def passwordHash = column[Option[String]]("password_hash")
    def email        = column[Option[String]]("email")

    def byEmail = index(s"${ prefix }users_email", email)

    /** The key is written from the username, so it can never disagree. */
    override def * = (id, username, usernameKey, passwordHash, email).<>(
      (id, username, _, passwordHash, email) =>
        UserRow(id, username, passwordHash, email),
      row =>
        Some((
          row.id,
          row.username,
          Username.key(row.username),
          row.passwordHash,
          row.email,
        )),
    )

  /** A table of sign-in sessions, with the columns of [[SessionRow]]. */
  final class Sessions
    (tag: Tag)
    extends Table[SessionRow](tag, s"${ prefix }sessions"):

    def tokenHash = column[String]("token_hash", O.PrimaryKey)
    def userId    = column[Long]("user_id")
    def expiresAt = column[Long]("expires_at")

    def byUser = index(s"${ prefix }sessions_user", userId)

    def byExpiry = index(s"${ prefix }sessions_expiry", expiresAt)

    override def * = (tokenHash, userId, expiresAt).mapTo[SessionRow]

  /** A table of user groups, with the columns of [[GroupRow]]. */
  final class Groups
    (tag: Tag)
    extends Table[GroupRow](tag, s"${ prefix }user_groups"):

    def id       = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name     = column[String]("name")
    def parentId = column[Option[Long]]("parent_id")
    def public   = column[Boolean]("public")

    def byParent = index(
      s"${ prefix }user_groups_parent",
      parentId,
    )

    def byPublic = index(s"${ prefix }user_groups_public", public)

    override def * = (id, name, parentId, public).mapTo[GroupRow]

  /** A table of group memberships, with the columns of [[MemberRow]]. */
  final class Members
    (tag: Tag)
    extends Table[MemberRow](tag, s"${ prefix }group_members"):

    def groupId = column[Long]("group_id")
    def userId  = column[Long]("user_id")

    def byGroup = index(
      s"${ prefix }group_members_group",
      (groupId, userId),
    )

    def byUser = index(s"${ prefix }group_members_user", userId)

    override def * = (groupId, userId).mapTo[MemberRow]

  /** A table of group invitations, with the columns of [[InvitationRow]]. */
  final class Invitations
    (tag: Tag)
    extends Table[InvitationRow](tag, s"${ prefix }group_invitations"):

    def id        = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def groupId   = column[Long]("group_id")
    def userId    = column[Long]("user_id")
    def inviterId = column[Long]("inviter_id")

    def byGroup = index(
      s"${ prefix }group_invitations_group",
      (groupId, userId),
    )

    def byUser = index(
      s"${ prefix }group_invitations_user",
      userId,
    )

    override def * = (id, groupId, userId, inviterId).mapTo[InvitationRow]

  /** A table of requests to join a group, with the columns of [[RequestRow]]. */
  final class Requests
    (tag: Tag)
    extends Table[RequestRow](tag, s"${ prefix }group_requests"):

    def groupId = column[Long]("group_id")
    def userId  = column[Long]("user_id")

    def byGroup = index(
      s"${ prefix }group_requests_group",
      (groupId, userId),
    )

    def byUser = index(
      s"${ prefix }group_requests_user",
      userId,
    )

    override def * = (groupId, userId).mapTo[RequestRow]

  /** A table of invite links, with the columns of [[InviteLinkRow]]. */
  final class InviteLinks
    (tag: Tag)
    extends Table[InviteLinkRow](tag, s"${ prefix }invite_links"):

    def code         = column[String]("code", O.PrimaryKey)
    def creatorId    = column[Long]("creator_id")
    def groupId      = column[Option[Long]]("group_id")
    def resourceKind = column[Option[String]]("resource_kind")
    def resourceId   = column[Option[Long]]("resource_id")
    def access       = column[Option[String]]("access")

    def byGroup = index(
      s"${ prefix }invite_links_group",
      groupId,
    )

    def byResource = index(
      s"${ prefix }invite_links_resource",
      (resourceKind, resourceId),
    )

    def byCreator = index(
      s"${ prefix }invite_links_creator",
      creatorId,
    )

    override def * = (
      code,
      creatorId,
      groupId,
      resourceKind,
      resourceId,
      access,
    ).mapTo[InviteLinkRow]

  /** A table of grants, with the columns of [[GrantRow]]. */
  final class Grants
    (tag: Tag)
    extends Table[GrantRow](tag, s"${ prefix }grants"):

    def id            = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def resourceKind  = column[String]("resource_kind")
    def resourceId    = column[Long]("resource_id")
    def principalKind = column[String]("principal_kind")
    def principalId   = column[Long]("principal_id")
    def access        = column[String]("access")

    def byResource = index(
      s"${ prefix }grants_resource",
      (resourceKind, resourceId),
    )

    def byPrincipal = index(
      s"${ prefix }grants_principal",
      (principalKind, principalId),
    )

    override def * = (
      id,
      resourceKind,
      resourceId,
      principalKind,
      principalId,
      access,
    ).mapTo[GrantRow]

  /** A table of unused recovery codes, with the columns of [[RecoveryCodeRow]]. */
  final class RecoveryCodes
    (tag: Tag)
    extends Table[RecoveryCodeRow](tag, s"${ prefix }recovery_codes"):

    def id       = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def userId   = column[Long]("user_id")
    def codeHash = column[String]("code_hash")

    def byUser = index(
      s"${ prefix }recovery_codes_user",
      userId,
    )

    override def * = (id, userId, codeHash).mapTo[RecoveryCodeRow]

  /** A table of links sent by email, with the columns of [[EmailLinkRow]]. */
  final class EmailLinks
    (tag: Tag)
    extends Table[EmailLinkRow](tag, s"${ prefix }email_links"):

    def tokenHash = column[String]("token_hash", O.PrimaryKey)
    def userId    = column[Long]("user_id")
    def purpose   = column[String]("purpose")
    def address   = column[String]("address")
    def sentAt    = column[Long]("sent_at")
    def expiresAt = column[Long]("expires_at")

    def byUser = index(s"${ prefix }email_links_user", userId)

    def byExpiry = index(
      s"${ prefix }email_links_expiry",
      expiresAt,
    )

    override def * = (tokenHash, userId, purpose, address, sentAt, expiresAt)
      .mapTo[EmailLinkRow]

  /**
    * A table of mails sent with links, kept for a day to limit how many are
    * sent (see [[UserStore.issueLink]]), with the columns of [[SentMailRow]].
    */
  final class SentMails
    (tag: Tag)
    extends Table[SentMailRow](tag, s"${ prefix }sent_mails"):

    def id      = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def userId  = column[Long]("user_id")
    def purpose = column[String]("purpose")
    def address = column[String]("address")
    def sentAt  = column[Long]("sent_at")

    def byUser = index(s"${ prefix }sent_mails_user", userId)

    def byAddress = index(
      s"${ prefix }sent_mails_address",
      address,
    )

    def bySent = index(s"${ prefix }sent_mails_sent", sentAt)

    override def * = (id, userId, purpose, address, sentAt).mapTo[SentMailRow]

  /**
    * A table of suspended accounts, with the columns of [[SuspensionRow]]: one
    * row for each, which nobody may sign in to while it stands.
    */
  final class Suspensions
    (tag: Tag)
    extends Table[SuspensionRow](tag, s"${ prefix }suspensions"):

    def userId = column[Long]("user_id", O.PrimaryKey)

    override def * = userId.<>(
      SuspensionRow.apply,
      row => Some(row.userId),
    )

  /** The table of users. */
  val users = TableQuery[Users]

  /** The table of sign-in sessions. */
  val sessions = TableQuery[Sessions]

  /** The table of user groups. */
  val groups = TableQuery[Groups]

  /** The table of group memberships. */
  val members = TableQuery[Members]

  /** The table of group invitations. */
  val invitations = TableQuery[Invitations]

  /** The table of requests to join a group. */
  val requests = TableQuery[Requests]

  /** The table of invite links. */
  val inviteLinks = TableQuery[InviteLinks]

  /** The table of grants. */
  val grants = TableQuery[Grants]

  /** The table of unused recovery codes. */
  val recoveryCodes = TableQuery[RecoveryCodes]

  /** The table of links sent by email. */
  val emailLinks = TableQuery[EmailLinks]

  /** The table of mails sent with links. */
  val sentMails = TableQuery[SentMails]

  /** The table of suspended accounts. */
  val suspensions = TableQuery[Suspensions]

  private val lockable = profile
    .capabilities
    .contains(JdbcCapabilities.forUpdate)

  /**
    * Locks the rows a query reads until the transaction ends, where the
    * database can. Always lock through this, never `forUpdate`: SQLite refuses
    * `FOR UPDATE`, and admits only one writer anyway.
    */
  private[server] def locked[E, U](query: Query[E, U, Seq]): Query[E, U, Seq] =
    if lockable then query.forUpdate else query

  /**
    * Reads one user's row, locking it until the transaction ends, where the
    * database can.
    *
    * @param id
    *   The user's identifier.
    *
    * @return
    *   An action reading the user's row, if they exist.
    */
  def lockUser(id: Long): DBIO[Option[UserRow]] =
    locked(users.filter(_.id === id)).result.headOption

  /**
    * Creates each of this library's tables the database lacks, with its
    * indexes, leaving existing tables untouched; safe on every startup. There
    * are no migrations: a table whose shape changed is not altered.
    *
    * Missing tables are found in the database's metadata, as
    * `CREATE TABLE IF NOT EXISTS` guards only the table, and Slick's separate
    * index statements would fail on a second startup.
    */
  def createIfNotExists: DBIO[Unit] = MTable
    .getTables
    .flatMap: held =>
      val names = held.map(_.name.name.toLowerCase(Locale.ROOT)).toSet
      DBIO.seq(createMissing(names)*)

  /**
    * Creates the tables not among the given lower-case names. Case is folded by
    * `Locale.ROOT`, so that a Turkish locale does not lower `I` to `ı`.
    */
  private def createMissing(held: Set[String]): Seq[DBIO[Unit]] = tables
    .filterNot(table =>
      held(table.baseTableRow.tableName.toLowerCase(Locale.ROOT)),
    )
    .map(_.schema.create)

  private def tables: Seq[TableQuery[? <: Table[?]]] = Seq(
    users,
    sessions,
    groups,
    members,
    invitations,
    requests,
    inviteLinks,
    grants,
    recoveryCodes,
    emailLinks,
    sentMails,
    suspensions,
  )

/**
  * A stored user, registered or a guest.
  *
  * @param id
  *   The identifier, assigned by the database.
  *
  * @param username
  *   The name the user signs in with, unique whatever its letter case, as
  *   [[Username.key]] folds it.
  *
  * @param passwordHash
  *   The password's hash as [[Passwords]] makes it, or `None` for a guest.
  *
  * @param email
  *   The confirmed email address, as [[EmailAddress.normalise]] writes it, or
  *   `None`. Several users may share one.
  */
final case class UserRow
  (
    id: Long,
    username: String,
    passwordHash: Option[String],
    email: Option[String],
  ):

  /** The user this row stores, without the password hash. */
  def toUser: User = User(id, username, passwordHash.isEmpty)

/**
  * A stored sign-in session.
  *
  * @param tokenHash
  *   The session token's hash as [[Digest]] makes it; the token itself is never
  *   stored.
  *
  * @param userId
  *   The identifier of the signed-in user.
  *
  * @param expiresAt
  *   The time the session stops being accepted, in milliseconds since the
  *   epoch, enforced however long the browser keeps the cookie.
  */
final case class SessionRow
  (
    tokenHash: String,
    userId: Long,
    expiresAt: Long,
  )

/**
  * A stored user group.
  *
  * @param id
  *   The identifier, assigned by the database.
  *
  * @param name
  *   The display name.
  *
  * @param parentId
  *   The identifier of the group this one is nested inside, or `None` for a
  *   top-level group.
  *
  * @param public
  *   Whether everyone can find the group and ask to join it.
  */
final case class GroupRow
  (
    id: Long,
    name: String,
    parentId: Option[Long],
    public: Boolean = false,
  ):

  /** The group this row stores. */
  def toGroup: Group = Group(id, name, parentId, public)

/**
  * A stored membership of a group.
  *
  * @param groupId
  *   The identifier of the group.
  *
  * @param userId
  *   The identifier of the member.
  */
final case class MemberRow(groupId: Long, userId: Long)

/**
  * A stored pending invitation to a group, at most one per user per group,
  * deleted once accepted or declined.
  *
  * @param id
  *   The identifier, assigned by the database.
  *
  * @param groupId
  *   The identifier of the group.
  *
  * @param userId
  *   The identifier of the invited user.
  *
  * @param inviterId
  *   The identifier of the user who sent the invitation.
  */
final case class InvitationRow
  (
    id: Long,
    groupId: Long,
    userId: Long,
    inviterId: Long,
  )

/**
  * A stored pending request to join a group, at most one per user per group,
  * deleted once admitted, declined or withdrawn.
  *
  * @param groupId
  *   The identifier of the group.
  *
  * @param userId
  *   The identifier of the user asking.
  */
final case class RequestRow(groupId: Long, userId: Long)

/**
  * A stored invite link, leading to either a group or a resource: one kind's
  * columns are filled and the other's empty.
  *
  * @param code
  *   The code in lower case (see [[InviteCode]]), stored unhashed so that its
  *   owners can copy the link again.
  *
  * @param creatorId
  *   The identifier of the user who made the link.
  *
  * @param groupId
  *   The identifier of the group the link leads to, if any.
  *
  * @param resourceKind
  *   The kind of the resource the link leads to, if any.
  *
  * @param resourceId
  *   The identifier of the resource among those of its kind, if any.
  *
  * @param access
  *   The stored code of the access following the link grants (see
  *   [[Access.code]]), if it leads to a resource.
  */
final case class InviteLinkRow
  (
    code: String,
    creatorId: Long,
    groupId: Option[Long],
    resourceKind: Option[String],
    resourceId: Option[Long],
    access: Option[String],
  ):

  /**
    * The place this link leads, or `None` if the row names nothing this version
    * can read, so that the link fails closed.
    */
  def target: Option[LinkTarget] = groupId
    .map(LinkTarget.Joining(_))
    .orElse(
      for
        kind  <- resourceKind
        id    <- resourceId
        level <- access.flatMap(Access.fromCode)
      yield LinkTarget.Sharing(Resource(kind, id), level),
    )

object InviteLinkRow:

  /**
    * Flattens a link into the row it is stored as.
    *
    * @param code
    *   The link's code, in lower case.
    *
    * @param creatorId
    *   The identifier of the user who made the link.
    *
    * @param target
    *   The place the link leads.
    *
    * @return
    *   A row storing the link.
    */
  def of
    (
      code: String,
      creatorId: Long,
      target: LinkTarget,
    )
    : InviteLinkRow = target match
    case LinkTarget.Joining(groupId) => InviteLinkRow(
        code,
        creatorId,
        Some(groupId),
        None,
        None,
        None,
      )
    case LinkTarget.Sharing(resource, access) => InviteLinkRow(
        code,
        creatorId,
        None,
        Some(resource.kind),
        Some(resource.id),
        Some(access.code),
      )

/**
  * A stored unused recovery code, deleted once used, or when its user generates
  * a new set.
  *
  * @param id
  *   The identifier, assigned by the database.
  *
  * @param userId
  *   The identifier of the user the code regains.
  *
  * @param codeHash
  *   The code's hash, as [[RecoveryCode.hash]] makes it.
  */
final case class RecoveryCodeRow(id: Long, userId: Long, codeHash: String)

/**
  * A stored link sent by email, deleted once used, and with its user's other
  * links whenever their password or address changes.
  *
  * @param tokenHash
  *   The hash of the link's secret, as [[Digest]] makes it.
  *
  * @param userId
  *   The identifier of the user the link was sent to.
  *
  * @param purpose
  *   The stored code of what the link does (see [[EmailPurpose.code]]).
  *
  * @param address
  *   The address the link was sent to; for a confirmation, the address it
  *   confirms.
  *
  * @param sentAt
  *   The time the link was sent, in milliseconds since the epoch.
  *
  * @param expiresAt
  *   The time the link stops working, in milliseconds since the epoch.
  */
final case class EmailLinkRow
  (
    tokenHash: String,
    userId: Long,
    purpose: String,
    address: String,
    sentAt: Long,
    expiresAt: Long,
  )

/**
  * A stored record of a mail sent, or tried, with a link.
  *
  * @param id
  *   The identifier, assigned by the database.
  *
  * @param userId
  *   The identifier of the user the link was for.
  *
  * @param purpose
  *   The stored code of what the link did (see [[EmailPurpose.code]]).
  *
  * @param address
  *   The address the mail was sent to.
  *
  * @param sentAt
  *   The time it was sent, in milliseconds since the epoch.
  */
final case class SentMailRow
  (
    id: Long,
    userId: Long,
    purpose: String,
    address: String,
    sentAt: Long,
  )

/**
  * A stored suspension of an account, deleted when it is lifted, released or
  * deleted.
  *
  * @param userId
  *   The identifier of the suspended account's user.
  */
final case class SuspensionRow(userId: Long)

/**
  * A kind of link sent by email.
  *
  * @param code
  *   The code it is stored under, spelled out so that renaming a case never
  *   changes what a stored row means.
  */
enum EmailPurpose(val code: String):

  /** A link resetting a forgotten password, sent to the confirmed address. */
  case Reset extends EmailPurpose("reset")

  /** A link confirming a new address, sent to that address. */
  case Confirm extends EmailPurpose("confirm")

/**
  * A stored grant. At most one exists per principal per resource, maintained by
  * [[GrantStore]].
  *
  * @param id
  *   The row's identifier, assigned by the database; a grant is identified by
  *   its resource and principal.
  *
  * @param resourceKind
  *   The kind of the resource, as the host names it.
  *
  * @param resourceId
  *   The identifier of the resource among those of its kind.
  *
  * @param principalKind
  *   The stored name of the kind of principal (see [[Principal.kind]]).
  *
  * @param principalId
  *   The identifier of the user or group.
  *
  * @param access
  *   The stored code of the level of access (see [[Access.code]]).
  */
final case class GrantRow
  (
    id: Long,
    resourceKind: String,
    resourceId: Long,
    principalKind: String,
    principalId: Long,
    access: String,
  ):

  /**
    * The grant this row stores, or `None` if it names a principal kind or level
    * this version does not know, so that the row confers nothing.
    */
  def toGrant: Option[Grant] =
    for
      principal <- Principal.of(principalKind, principalId)
      level     <- Access.fromCode(access)
    yield Grant(
      Resource(resourceKind, resourceId),
      principal,
      level,
    )

object GrantRow:

  /**
    * Flattens a grant into the row it is stored as.
    *
    * @param grant
    *   The grant to flatten.
    *
    * @return
    *   A row awaiting its identifier.
    */
  def of(grant: Grant): GrantRow = GrantRow(
    0,
    grant.resource.kind,
    grant.resource.id,
    grant.principal.kind,
    grant.principal.id,
    grant.access.code,
  )
