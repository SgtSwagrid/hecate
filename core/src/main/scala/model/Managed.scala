package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * Someone who manages an account: a principal holding a grant over it
  * ([[Resource.user]]), and how far.
  *
  * @param holder
  *   The user, group or system holding the grant, with its name.
  *
  * @param access
  *   The access held: [[Access.View]] to see the account, [[Access.Edit]] to
  *   rename it, set its password, suspend it and answer its invitations, and
  *   [[Access.Own]] to delete it, release it and choose who manages it.
  */
final case class Manager(holder: Holder, access: Access) derives Codec.AsObject

/**
  * An account as someone who manages it sees it: anyone holding at least
  * [[Access.View]] over it, never its own user.
  *
  * @param user
  *   The account's user.
  *
  * @param access
  *   The access the reader holds over the account.
  *
  * @param suspended
  *   Whether the account is suspended, so that nobody can sign in to it.
  *
  * @param managers
  *   Everyone holding a grant over the account, owners first.
  *
  * @param invitations
  *   The invitations to groups awaiting the account's answer, which anyone who
  *   may change it may give.
  */
final case class ManagedAccount
  (
    user: User,
    access: Access,
    suspended: Boolean,
    managers: List[Manager],
    invitations: List[Invitation] = List.empty,
  )
  derives Codec.AsObject

/**
  * One account to create for someone else to use.
  *
  * @param username
  *   The username the account signs in with.
  *
  * @param password
  *   The password it signs in with, or `None` to have one generated.
  */
final case class NewAccount
  (
    username: String,
    password: Option[String] = None,
  )
  derives Codec.AsObject

/**
  * A request to create accounts that the signed-in user, or a principal they
  * name, manages.
  *
  * @param accounts
  *   The accounts to create, all or none.
  *
  * @param group
  *   The group each account is to join at once, which the signed-in user must
  *   be able to change, or `None` for none.
  *
  * @param owner
  *   The principal to hold [[Access.Own]] over each account, which the
  *   signed-in user must be able to address, or `None` for the signed-in user.
  */
final case class AccountCreation
  (
    accounts: List[NewAccount],
    group: Option[Long] = None,
    owner: Option[Principal] = None,
  )
  derives Codec.AsObject

/**
  * An account just created, with the password it signs in with, which is shown
  * this once and never stored unhashed.
  *
  * @param user
  *   The account's user.
  *
  * @param password
  *   The password, as given or as generated.
  */
final case class CreatedAccount(user: User, password: String)
  derives Codec.AsObject

/**
  * A new username for an account.
  *
  * @param username
  *   The username it is to sign in with.
  */
final case class UsernameChange(username: String) derives Codec.AsObject

/**
  * A password set for an account by someone who manages it.
  *
  * @param password
  *   The new password.
  */
final case class PasswordSetting(password: String) derives Codec.AsObject
