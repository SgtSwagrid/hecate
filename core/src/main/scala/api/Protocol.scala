package com.alecdorrington.hecate
package api

/**
  * The cookies and text length limits that every server and client of this
  * library's API agree on. The server enforces the limits, so that nothing
  * longer is ever stored, hashed or compared.
  */
object Protocol:

  /** The name of the cookie that holds the session token. */
  val sessionCookie: String = "auth_session"

  /**
    * The name of the cookie naming the language refusals are worded in. The
    * client sets it for its whole origin; the server only passes its value on
    * to the host's wording.
    */
  val languageCookie: String = "language"

  /** The most characters a username, group name or recovery code may have. */
  val maxNameLength: Int = 256

  /**
    * The most characters the name a guest gives may have: a username's, less
    * room for the number that tells two guests of the same name apart.
    */
  val maxGuestNameLength: Int = maxNameLength - 16

  /**
    * The most characters a password may have. Hashing takes time in proportion
    * to length, before anyone is authenticated, so longer passwords are refused
    * before they are hashed.
    */
  val maxPasswordLength: Int = 1024

  /** The most characters an email address may have, as mail allows. */
  val maxEmailLength: Int = 254

  /**
    * The most accounts one request may create. Each password is hashed before
    * anything is stored, which takes around a tenth of a second.
    */
  val maxAccountsPerRequest: Int = 200
