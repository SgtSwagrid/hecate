package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A user, registered or a guest.
  *
  * @param id
  *   The identifier of the user, assigned by the store.
  *
  * @param username
  *   The unique name the user signs in with, shown as chosen but matched
  *   whatever its letter case (`Alice` signs in as `alice`). A guest's is the
  *   name they gave, numbered if taken.
  *
  * @param guest
  *   Whether this is a guest: an account made to follow an invite link, with no
  *   password, reachable only through its one session until claimed with a
  *   username and password.
  */
final case class User
  (
    id: Long,
    username: String,
    guest: Boolean = false,
  )
  derives Codec.AsObject

/**
  * The credentials submitted to register, sign in, or claim a guest account.
  *
  * @param username
  *   The username of the account.
  *
  * @param password
  *   The password, in plain text. The server stores only a salted hash.
  */
final case class Credentials(username: String, password: String)
  derives Codec.AsObject

/**
  * A request to follow an invite link as a guest.
  *
  * @param name
  *   The name the guest gives, which becomes their username.
  */
final case class Guest(name: String) derives Codec.AsObject

/**
  * The result of following an invite link as a guest.
  *
  * @param user
  *   The guest, now signed in.
  *
  * @param target
  *   The target the link gave them.
  */
final case class Welcome(user: User, target: LinkTarget) derives Codec.AsObject
