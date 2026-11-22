package com.alecdorrington.hecate
package tapir

import com.alecdorrington.hecate.api.Protocol.{
  maxEmailLength, maxGuestNameLength, maxNameLength, maxPasswordLength,
}
import com.alecdorrington.hecate.model.{
  Access, AccountCreation, AuthRules, CreatedAccount, Credentials, EmailChange,
  EmailConfirmation, EmailStatus, Gated, Grant, Group, GroupDetails,
  GroupInvitation, Guest, Holder, Invitation, Invitee, InviteLink, InviteResult,
  InviteStatus, JoinableGroup, LinkPreview, LinkTarget, ManagedAccount,
  ManagedGroup, Manager, Membership, NewAccount, PasswordChange, PasswordCheck,
  PasswordReplacement, PasswordReset, PasswordResetRequest, PasswordSet,
  PasswordSetting, Permitted, Principal, Recovery, RecoveryCodes, Resource,
  User, UsernameChange, Welcome,
}
import sttp.tapir.{FieldName, Schema, SchemaType, Validator}

/**
  * The Tapir schemas of the models the endpoints carry. Every string is bounded
  * by a length in [[com.alecdorrington.hecate.api.Protocol]], so longer text is
  * refused before it reaches the server's logic.
  *
  * Public, so that a host's endpoints carrying the model import these rather
  * than derive their own, which would be ambiguous beside them.
  */
object Schemas:

  private def bounded(max: Int): Schema[String] = Schema
    .schemaForString
    .validate(Validator.maxLength(max))

  // Bounds every name; passwords and email addresses are bounded below.
  private given Schema[String] = bounded(maxNameLength)

  given Schema[User]            = Schema.derived
  given Schema[AuthRules]       = Schema.derived
  given Schema[RecoveryCodes]   = Schema.derived
  given Schema[Group]           = Schema.derived
  given Schema[ManagedGroup]    = Schema.derived
  given Schema[GroupDetails]    = Schema.derived
  given Schema[Invitation]      = Schema.derived
  given Schema[GroupInvitation] = Schema.derived
  given Schema[JoinableGroup]   = Schema.derived
  given Schema[Membership]      = Schema.derived

  /**
    * The schema of an invitee as its codec sends it: its kind, then a person's
    * username or a group's id.
    */
  given Schema[Invitee] = Schema(
    SchemaType.SProduct(List(
      SchemaType.SProductField[Invitee, String](
        FieldName("kind"),
        Schema
          .string
          .validate(Validator.enumeration(
            List(Invitee.personKind, Invitee.groupKind),
          )),
        invitee => Some(invitee.kind),
      ),
      SchemaType.SProductField[Invitee, Option[String]](
        FieldName("username"),
        summon[Schema[String]].asOption,
        {
          case Invitee.Person(username) => Some(Some(username))
          case _                        => None
        },
      ),
      SchemaType.SProductField[Invitee, Option[Long]](
        FieldName("id"),
        Schema.schemaForLong.asOption,
        {
          case Invitee.Group(id) => Some(Some(id))
          case _                 => None
        },
      ),
    )),
    Some(Schema.SName("Invitee")),
  )

  given Schema[InviteStatus] = Schema
    .string
    .validate(Validator.enumeration(
      InviteStatus.values.toList,
      status => Some(status.code),
    ))

  given Schema[Access] = Schema
    .string
    .validate(Validator.enumeration(
      Access.values.toList,
      level => Some(level.code),
    ))

  given Schema[Resource]   = Schema.derived
  given Schema[InviteLink] = Schema.derived

  /** The schema of a principal as its codec sends it: its kind, then its id. */
  given Schema[Principal] = Schema(
    SchemaType.SProduct(List(
      SchemaType.SProductField[Principal, String](
        FieldName("kind"),
        Schema
          .string
          .validate(Validator.enumeration(List(
            Principal.personKind,
            Principal.groupKind,
            Principal.systemKind,
          ))),
        principal => Some(principal.kind),
      ),
      SchemaType.SProductField[Principal, Long](
        FieldName("id"),
        Schema.schemaForLong,
        principal => Some(principal.id),
      ),
    )),
    Some(Schema.SName("Principal")),
  )

  given Schema[Holder] = Schema.derived

  given Schema[InviteResult] = Schema.derived

  given Schema[Grant] = Schema.derived

  given Schema[Manager]        = Schema.derived
  given Schema[PasswordSet]    = Schema.derived
  given Schema[ManagedAccount] = Schema.derived
  given Schema[UsernameChange] = Schema.derived

  given Schema[NewAccount] = Schema
    .derived[NewAccount]
    .modify(_.password)(_ => bounded(maxPasswordLength).asOption)

  given Schema[AccountCreation] = Schema.derived

  given Schema[CreatedAccount] = Schema
    .derived[CreatedAccount]
    .modify(_.password)(_ => bounded(maxPasswordLength))

  given Schema[PasswordSetting] = Schema
    .derived[PasswordSetting]
    .modify(_.password)(_ => bounded(maxPasswordLength))

  /** The schema of a value beside its reader's access. */
  given [X : Schema]: Schema[Permitted[X]] = Schema.derived

  /** The schema of a gated part: its `state`, with its `value` when shown. */
  given [X](using inner: Schema[X]): Schema[Gated[X]] = Schema(
    SchemaType.SProduct(List(
      SchemaType.SProductField[Gated[X], String](
        FieldName("state"),
        Schema
          .string
          .validate(Validator.enumeration(List(
            Gated.shownState,
            Gated.absentState,
            Gated.withheldState,
          ))),
        gated => Some(state(gated)),
      ),
      SchemaType.SProductField[Gated[X], Option[X]](
        FieldName("value"),
        inner.asOption,
        {
          case Gated.Shown(value) => Some(Some(value))
          case _                  => None
        },
      ),
    )),
    Some(Schema.SName("Gated")),
  )

  // Declares only the kind, the one field every target has.
  given Schema[LinkTarget] = Schema(
    SchemaType.SProduct(List(SchemaType.SProductField[LinkTarget, String](
      FieldName("kind"),
      Schema
        .string
        .validate(Validator.enumeration(List(
          LinkTarget.groupKind,
          LinkTarget.resourceKind,
        ))),
      target => Some(target.kind),
    ))),
    Some(Schema.SName("LinkTarget")),
  )

  given Schema[LinkPreview] = Schema.derived
  given Schema[Welcome]     = Schema.derived

  given Schema[Guest] = Schema
    .derived[Guest]
    .modify(_.name)(_ => bounded(maxGuestNameLength))

  given Schema[Credentials] = Schema
    .derived[Credentials]
    .modify(_.password)(_ => bounded(maxPasswordLength))

  given Schema[PasswordChange] = Schema
    .derived[PasswordChange]
    .modify(_.current)(_ => bounded(maxPasswordLength))
    .modify(_.replacement)(_ => bounded(maxPasswordLength))

  given Schema[PasswordReplacement] = Schema
    .derived[PasswordReplacement]
    .modify(_.current)(_ => bounded(maxPasswordLength))
    .modify(_.replacement)(_ => bounded(maxPasswordLength))

  given Schema[PasswordCheck] = Schema
    .derived[PasswordCheck]
    .modify(_.password)(_ => bounded(maxPasswordLength))

  given Schema[Recovery] = Schema
    .derived[Recovery]
    .modify(_.replacement)(_ => bounded(maxPasswordLength))

  given Schema[EmailStatus]       = Schema.derived
  given Schema[EmailConfirmation] = Schema.derived

  given Schema[EmailChange] = Schema
    .derived[EmailChange]
    .modify(_.address)(_ => bounded(maxEmailLength).asOption)
    .modify(_.password)(_ => bounded(maxPasswordLength))

  given Schema[PasswordResetRequest] = Schema
    .derived[PasswordResetRequest]
    .modify(_.address)(_ => bounded(maxEmailLength))

  given Schema[PasswordReset] = Schema
    .derived[PasswordReset]
    .modify(_.replacement)(_ => bounded(maxPasswordLength))

  /** The state a gated part is sent under. */
  private def state(gated: Gated[?]): String = gated match
    case Gated.Shown(_) => Gated.shownState
    case Gated.Absent   => Gated.absentState
    case Gated.Withheld => Gated.withheldState
