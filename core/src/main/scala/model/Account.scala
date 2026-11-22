package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A request to change the signed-in user's password.
  *
  * @param current
  *   The user's current password.
  *
  * @param replacement
  *   The new password.
  */
final case class PasswordChange(current: String, replacement: String)
  derives Codec.AsObject

/**
  * A request to sign in to an account whose password someone else set,
  * replacing it with one of the user's own.
  *
  * @param username
  *   The username of the account.
  *
  * @param current
  *   The password someone else set.
  *
  * @param replacement
  *   The user's own password, which must differ from it.
  */
final case class PasswordReplacement
  (
    username: String,
    current: String,
    replacement: String,
  )
  derives Codec.AsObject

/**
  * A password given again to confirm a consequential request, such as deleting
  * an account.
  *
  * @param password
  *   The signed-in user's password. Not read for a guest, who has none.
  */
final case class PasswordCheck(password: String) derives Codec.AsObject

/**
  * A request to regain an account with one of its recovery codes.
  *
  * @param username
  *   The username of the account.
  *
  * @param code
  *   The unused recovery code. Spacing, hyphens and case are ignored.
  *
  * @param replacement
  *   The new password.
  */
final case class Recovery
  (
    username: String,
    code: String,
    replacement: String,
  )
  derives Codec.AsObject

/**
  * A new set of recovery codes, shown to their owner once. Each regains the
  * account once, and generating a new set invalidates every earlier code.
  *
  * @param codes
  *   The codes, formatted to be written down.
  */
final case class RecoveryCodes(codes: List[String]) derives Codec.AsObject

/**
  * The server's rules for accounts, so that a client can explain them before a
  * request is refused.
  *
  * @param minPasswordLength
  *   The fewest characters a password may have.
  *
  * @param accountDeletion
  *   Whether users may delete their own accounts.
  *
  * @param email
  *   Whether the server sends email, so that users may reset a forgotten
  *   password through their address.
  *
  * @param guests
  *   Whether someone signed out may follow an invite link as a guest.
  */
final case class AuthRules
  (
    minPasswordLength: Int,
    accountDeletion: Boolean,
    email: Boolean,
    guests: Boolean,
  )
  derives Codec.AsObject

/**
  * The signed-in user's email address, shown only to them.
  *
  * @param address
  *   The confirmed address, or `None` if there is none.
  *
  * @param pending
  *   The address awaiting confirmation through the link sent to it, or `None`
  *   if there is none.
  */
final case class EmailStatus
  (
    address: Option[String],
    pending: Option[String],
  )
  derives Codec.AsObject

/**
  * A request to change the signed-in user's email address. A new address
  * replaces the old one only once the link sent to it is opened.
  *
  * @param address
  *   The new address, or `None` to remove the address.
  *
  * @param password
  *   The user's password, required because the address can reset it.
  */
final case class EmailChange
  (
    address: Option[String],
    password: String,
  )
  derives Codec.AsObject

/**
  * A request to email a link resetting a forgotten password.
  *
  * @param address
  *   The email address of the account.
  */
final case class PasswordResetRequest(address: String) derives Codec.AsObject

/**
  * A request to reset a forgotten password with a link sent by email.
  *
  * @param token
  *   The secret the link carries.
  *
  * @param replacement
  *   The new password.
  */
final case class PasswordReset(token: String, replacement: String)
  derives Codec.AsObject

/**
  * A request to confirm an email address with the link sent to it.
  *
  * @param token
  *   The secret the link carries.
  */
final case class EmailConfirmation(token: String) derives Codec.AsObject
