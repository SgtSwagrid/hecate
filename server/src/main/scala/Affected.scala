package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.Resource

/**
  * A report of whom a committed change concerns, for the host to notify. It
  * says nothing of what changed: whoever is told should ask again through the
  * endpoints that check what they may see, so a notice discloses nothing. The
  * exceptions, [[Affected.Invited]] and [[Affected.PasswordSet]], say what
  * happened for a host that tells the person it happened to.
  */
enum Affected:

  /**
    * A change to groups: their names, nesting, visibility, invite links,
    * members, invitations or requests to join.
    *
    * @param audience
    *   The people whose view of any of the groups may have changed.
    *
    * @param groups
    *   The groups whose members may have changed, with every group enclosing
    *   them. Anything the host addressed to one of these may now reach
    *   different people.
    */
  case Groups(audience: Audience, groups: Set[Long])

  /**
    * An invitation sent, reported beside the change to its group's members.
    *
    * @param invitation
    *   The identifier of the invitation.
    *
    * @param group
    *   The identifier of the group the invitee is invited to join.
    *
    * @param invitee
    *   The identifier of the user invited.
    */
  case Invited
    (
      invitation: Long,
      group: Long,
      invitee: Long,
    )

  /**
    * A change to the grants over a resource, such as an invite link followed or
    * an owner sharing it. The host decides whom this concerns.
    *
    * @param resource
    *   The resource whose grants changed.
    *
    * @param formerly
    *   The users its grants reached before, who may have lost it.
    */
  case Grants
    (
      resource: Resource,
      formerly: Set[Long] = Set.empty,
    )

  /**
    * A change to an account: a session closed, a password, email address or
    * recovery codes replaced, a suspension, or the account renamed or deleted.
    *
    * @param user
    *   The identifier of the account's user.
    */
  case Account(user: Long)

  /**
    * A change to accounts as their managers see them: who manages them, their
    * names, their suspension, or the invitations awaiting them.
    *
    * @param people
    *   The people whose view of the accounts may have changed: whoever manages
    *   them, before the change and after, and the accounts' own users, who see
    *   who manages them.
    */
  case Accounts(people: Set[Long])

  /**
    * A password set for an account by someone who manages it, reported beside
    * the change to the account, for a host that tells the account's user who
    * did it.
    *
    * @param user
    *   The identifier of the account's user.
    *
    * @param by
    *   The identifier of the user who set the password.
    */
  case PasswordSet(user: Long, by: Long)

/** A set of people a change concerns. */
enum Audience:

  /** Everyone, as when a public group changed. */
  case Everyone

  /**
    * Particular people.
    *
    * @param ids
    *   The identifiers of the users.
    */
  case People(ids: Set[Long])
