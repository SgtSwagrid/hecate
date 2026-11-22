package com.alecdorrington.hecate
package i18n

import com.alecdorrington.hecate.model.AuthRefusal

/**
  * Everything this library says to the user, in one language. A host implements
  * it once per language it speaks, and [[Wording.english]] is used where it
  * gives none. Adding a phrase fails every language until it is translated.
  */
trait Wording:

  /** The refusal of a request that needs a signed-in user. */
  val signedOut: String

  /** The refusal of a username that is taken. */
  val usernameTaken: String

  /**
    * The refusal of a failed sign-in, which must not say whether the username
    * or the password was wrong.
    */
  val credentialsIncorrect: String

  /** The refusal of a wrong password given again to confirm a request. */
  val passwordIncorrect: String

  /** The refusal of a failed recovery, which must not say which part was wrong. */
  val recoveryIncorrect: String

  /** The refusal to delete an account where the host offers no deletion. */
  val noAccountDeletion: String

  /** The refusal of a registration with a blank username. */
  val usernameEmpty: String

  /** The refusal of someone continuing as a guest without giving a name. */
  val guestNameEmpty: String

  /** The refusal of a guest where the host lets nobody continue as one. */
  val noGuests: String

  /**
    * The refusal of a guest while guests are paused after too many links that
    * lead nowhere. It must not say whether their link works, and should offer
    * signing in instead.
    */
  val guestsPaused: String

  /** The refusal to claim an account that already has a password. */
  val notGuest: String

  /**
    * Words the refusal of a password that is too short.
    *
    * @param min
    *   The fewest characters a password may have.
    *
    * @return
    *   A sentence stating the fewest characters allowed.
    */
  def passwordTooShort(min: Int): String

  /**
    * Words the refusal of a name, code or password that is too long.
    *
    * @param max
    *   The most characters it may have.
    *
    * @return
    *   A sentence stating the most characters allowed.
    */
  def tooLong(max: Int): String

  /**
    * Words the refusal of an invitation to a username that nobody has.
    *
    * @param username
    *   The username invited.
    *
    * @return
    *   A sentence naming the username.
    */
  def userMissing(username: String): String

  /** The refusal of a group that does not exist, or is somebody else's. */
  val groupMissing: String

  /** The refusal of an invitation that does not exist, or is somebody else's. */
  val invitationMissing: String

  /**
    * The refusal to admit someone to a group who has not asked to join it, or
    * has withdrawn their request.
    */
  val requestMissing: String

  /**
    * The refusal of an invite link that leads nowhere: it never existed, or was
    * replaced or turned off.
    */
  val linkMissing: String

  /** The refusal of a parent group that does not exist. */
  val parentGroupMissing: String

  /** The refusal to nest a group inside itself. */
  val groupInsideItself: String

  /**
    * Words the refusal to delete an account that is the only owner of some
    * resources.
    *
    * @param count
    *   The number of resources the account alone owns.
    *
    * @return
    *   A sentence stating the number.
    */
  def soleOwner(count: Int): String

  /**
    * Words the refusal of a resource that does not exist or that the caller may
    * not see.
    *
    * @param kind
    *   The kind of the resource, as the host names it.
    *
    * @return
    *   A sentence naming the kind.
    */
  def resourceMissing(kind: String): String

  /**
    * Words the refusal to show or change who holds a resource to someone who
    * does not own it.
    *
    * @param kind
    *   The kind of the resource, as the host names it.
    *
    * @return
    *   A sentence naming the kind.
    */
  def notOwner(kind: String): String

  /**
    * Words the refusal of a change that would leave a resource with nobody
    * owning it.
    *
    * @param kind
    *   The kind of the resource, as the host names it.
    *
    * @return
    *   A sentence naming the kind.
    */
  def lastOwner(kind: String): String

  /** The refusal of an invite link that would make whoever follows it an owner. */
  val ownershipByLink: String

  /**
    * The refusal of a user or group that does not exist or cannot be addressed.
    * A `def`, so that a host may word it with phrases of its own.
    */
  def principalMissing: String

  /** The refusal of an email address that is not valid. */
  val emailInvalid: String

  /**
    * The refusal of a link sent by email that has expired, been used or never
    * existed, which must not say which.
    */
  val emailLinkMissing: String

  /** The refusal to send email where the host sends none. */
  val noEmail: String

  /** The refusal of a request for an email too soon after the last one. */
  val mailedRecently: String

  /**
    * The refusal to sign in to, or regain, an account its managers have
    * suspended.
    */
  val suspended: String

  /**
    * The refusal of something only an account's managers may do, such as
    * deleting it, asked by the account's own user.
    */
  val accountManaged: String

  /**
    * The refusal to manage one's own account, or to grant someone access over
    * their own account.
    */
  val managingSelf: String

  /**
    * Words the refusal to create accounts whose usernames are taken or
    * repeated.
    *
    * @param usernames
    *   The usernames taken or repeated.
    *
    * @return
    *   A sentence naming the usernames.
    */
  def usernamesTaken(usernames: List[String]): String

  /**
    * Words the refusal to create more accounts at once than allowed.
    *
    * @param max
    *   The most accounts one request may create.
    *
    * @return
    *   A sentence stating the number.
    */
  def tooManyAccounts(max: Int): String

  /**
    * The refusal of a request that failed for an internal reason, the same for
    * every failure so that its cause cannot be guessed.
    */
  val requestFailed: String

  /** The client's message for a reply it could not read. */
  val unreadableReply: String

  /** The client's message for a request that never reached the server. */
  val unreachable: String

  /**
    * The client's message once a password has been changed, or reset with a
    * link sent by email.
    */
  val passwordChanged: String

  /**
    * The client's message once a guest has claimed their account, which they
    * now sign in to with the username and password they chose.
    */
  val accountClaimed: String

  /**
    * The client's message once a link to reset a forgotten password has been
    * asked for, which must not say whether any account has the address.
    */
  val resetLinkSent: String

  /**
    * Words the client's message once a link to confirm an email address has
    * been sent to it.
    *
    * @param address
    *   The address the link was sent to.
    *
    * @return
    *   A sentence naming the address.
    */
  def confirmationSent(address: String): String

  /** The client's message once an email address has been confirmed. */
  val emailConfirmed: String

  /** The client's message once an email address has been removed. */
  val emailRemoved: String

  /**
    * Words the subject of the email with a link to reset a forgotten password.
    *
    * @param site
    *   The name of the website the account is on.
    *
    * @return
    *   A subject line.
    */
  def resetMailSubject(site: String): String

  /**
    * Words the email with a link to reset a forgotten password.
    *
    * @param site
    *   The name of the website the account is on.
    *
    * @param username
    *   The username of the account, as one address may have several.
    *
    * @param link
    *   The link that resets the password.
    *
    * @param hours
    *   The number of hours the link works for.
    *
    * @return
    *   A plain-text body, with the link on a line of its own.
    */
  def resetMailBody
    (
      site: String,
      username: String,
      link: String,
      hours: Int,
    )
    : String

  /**
    * Words the subject of the email with a link to confirm an email address.
    *
    * @param site
    *   The name of the website the account is on.
    *
    * @return
    *   A subject line.
    */
  def confirmMailSubject(site: String): String

  /**
    * Words the email with a link to confirm an email address. It must name no
    * account, as anyone may type the address.
    *
    * @param site
    *   The name of the website the account is on.
    *
    * @param link
    *   The link that confirms the address.
    *
    * @param hours
    *   The number of hours the link works for.
    *
    * @return
    *   A plain-text body, with the link on a line of its own.
    */
  def confirmMailBody(site: String, link: String, hours: Int): String

  /**
    * Words the subject of the email telling an address that it is no longer an
    * account's.
    *
    * @param site
    *   The name of the website the account is on.
    *
    * @return
    *   A subject line.
    */
  def addressChangedMailSubject(site: String): String

  /**
    * Words the email telling an address that its account replaced or removed
    * it, saying what to do if the account's owner did not make the change.
    *
    * @param site
    *   The name of the website the account is on.
    *
    * @param username
    *   The username of the account.
    *
    * @return
    *   A plain-text body.
    */
  def addressChangedMailBody(site: String, username: String): String

  /**
    * Words a refusal.
    *
    * @param refusal
    *   The refusal to word.
    *
    * @return
    *   A sentence the user reads.
    */
  final def phrase(refusal: AuthRefusal): String = refusal match
    case AuthRefusal.SignedOut             => signedOut
    case AuthRefusal.UsernameTaken         => usernameTaken
    case AuthRefusal.CredentialsIncorrect  => credentialsIncorrect
    case AuthRefusal.PasswordIncorrect     => passwordIncorrect
    case AuthRefusal.RecoveryIncorrect     => recoveryIncorrect
    case AuthRefusal.NoAccountDeletion     => noAccountDeletion
    case AuthRefusal.UsernameEmpty         => usernameEmpty
    case AuthRefusal.GuestNameEmpty        => guestNameEmpty
    case AuthRefusal.NoGuests              => noGuests
    case AuthRefusal.GuestsPaused          => guestsPaused
    case AuthRefusal.NotGuest              => notGuest
    case AuthRefusal.PasswordTooShort(min) => passwordTooShort(min)
    case AuthRefusal.TooLong(max)          => tooLong(max)
    case AuthRefusal.UserMissing(username) => userMissing(username)
    case AuthRefusal.GroupMissing          => groupMissing
    case AuthRefusal.InvitationMissing     => invitationMissing
    case AuthRefusal.RequestMissing        => requestMissing
    case AuthRefusal.LinkMissing           => linkMissing
    case AuthRefusal.ParentGroupMissing    => parentGroupMissing
    case AuthRefusal.GroupInsideItself     => groupInsideItself
    case AuthRefusal.SoleOwner(count)      => soleOwner(count)
    case AuthRefusal.ResourceMissing(kind) => resourceMissing(kind)
    case AuthRefusal.NotOwner(kind)        => notOwner(kind)
    case AuthRefusal.LastOwner(kind)       => lastOwner(kind)
    case AuthRefusal.OwnershipByLink       => ownershipByLink
    case AuthRefusal.PrincipalMissing      => principalMissing
    case AuthRefusal.EmailInvalid          => emailInvalid
    case AuthRefusal.EmailLinkMissing      => emailLinkMissing
    case AuthRefusal.NoEmail               => noEmail
    case AuthRefusal.MailedRecently        => mailedRecently
    case AuthRefusal.Suspended             => suspended
    case AuthRefusal.AccountManaged        => accountManaged
    case AuthRefusal.ManagingSelf          => managingSelf
    case AuthRefusal.UsernamesTaken(names) => usernamesTaken(names)
    case AuthRefusal.TooManyAccounts(max)  => tooManyAccounts(max)
    case AuthRefusal.Failed                => requestFailed

object Wording:

  /** The library's own English, used where the host gives no wording. */
  val english: Wording = new Wording:

    override val signedOut: String = "You need to sign in to do this."

    override val usernameTaken: String = "That username is already taken."

    override val credentialsIncorrect: String =
      "Incorrect username or password."

    override val passwordIncorrect: String = "Incorrect password."

    override val recoveryIncorrect: String =
      "Incorrect username or recovery code."

    override val noAccountDeletion: String = "Accounts can't be deleted here."

    override val usernameEmpty: String = "Username can't be empty."

    override val guestNameEmpty: String = "Enter your name."

    override val noGuests: String =
      "You need an account to follow invite links here."

    override val guestsPaused: String =
      "Too many invite links that don't work have been tried lately, so " +
        "joining as a guest is paused for a while. Sign in or create an " +
        "account instead, or try again later."

    override val notGuest: String = "This account has a password already."

    override def passwordTooShort(min: Int): String =
      s"Password must be at least $min characters."

    override def tooLong(max: Int): String =
      s"That's too long: it can have at most $max characters."

    override def userMissing(username: String): String =
      s"No user is called \"$username\"."

    override val groupMissing: String = "That group doesn't exist."

    override val invitationMissing: String = "That invitation doesn't exist."

    override val requestMissing: String =
      "That request to join doesn't exist any more."

    override val linkMissing: String =
      "That invite link doesn't work. Ask whoever sent it for a new one."

    override val parentGroupMissing: String = "The parent group doesn't exist."

    override val groupInsideItself: String =
      "A group can't be its own subgroup."

    override def soleOwner(count: Int): String =
      val things = if count == 1 then "1 thing" else s"$count things"
      s"You're the only owner of $things. Give them to someone else, or " +
        "delete them, before deleting your account."

    override def resourceMissing(kind: String): String = s"No such $kind."

    override def notOwner(kind: String): String =
      s"Only an owner of this $kind can share it."

    override def lastOwner(kind: String): String =
      s"This $kind needs at least one owner."

    override val ownershipByLink: String =
      "An invite link can't make anyone an owner."

    override def principalMissing: String = "No such person or group."

    override val emailInvalid: String = "That isn't a valid email address."

    override val emailLinkMissing: String =
      "That link has expired, or has been used already. Ask for a new one."

    override val noEmail: String = "This site doesn't send email."

    override val mailedRecently: String =
      "An email was sent to you a moment ago. Wait a little before asking " +
        "for another."

    override val suspended: String =
      "This account is suspended. Ask whoever manages it."

    override val accountManaged: String =
      "Your account is managed by someone else, who alone may do this."

    override val managingSelf: String =
      "Nobody can manage their own account, or be given access over it."

    override def usernamesTaken(usernames: List[String]): String =
      val quoted = usernames.map(name => s"\"$name\"").mkString(", ")
      if usernames.sizeIs == 1 then s"The username $quoted is already taken."
      else s"These usernames are already taken: $quoted."

    override def tooManyAccounts(max: Int): String =
      s"At most $max accounts can be created at once."

    override val requestFailed: String = "The request couldn't be completed."

    override val unreadableReply: String =
      "The server sent a reply that couldn't be read."

    override val unreachable: String = "The server couldn't be reached."

    override val passwordChanged: String =
      "Your password has been changed, and every other session signed out."

    override val accountClaimed: String =
      "Your account is ready. From now on, sign in with your username and " +
        "password."

    override val resetLinkSent: String =
      "If an account has that email address, a link to choose a new password " +
        "is on its way to it."

    override def confirmationSent(address: String): String =
      s"We've sent a link to $address. Open it to confirm the address."

    override val emailConfirmed: String = "Your email address is confirmed."

    override val emailRemoved: String = "Your email address has been removed."

    override def resetMailSubject(site: String): String =
      s"Reset your password for $site"

    override def resetMailBody
      (
        site: String,
        username: String,
        link: String,
        hours: Int,
      )
      : String =
      s"Someone asked to reset the password of your account \"$username\" " +
        s"on $site.\n\nTo choose a new password, open this link within " +
        s"${ within(hours) }:\n\n$link\n\nIf it wasn't you, ignore this " +
        "email, and your password stays as it is.\n"

    override def confirmMailSubject(site: String): String =
      s"Confirm your email address for $site"

    override def confirmMailBody
      (site: String, link: String, hours: Int)
      : String =
      s"Someone asked to use this email address for an account on $site.\n\n" +
        s"To confirm it, open this link within ${ within(hours) }:\n\n" +
        s"$link\n\nIf it wasn't you, ignore this email, and the address " +
        "won't be used.\n"

    override def addressChangedMailSubject(site: String): String =
      s"Your email address for $site has changed"

    override def addressChangedMailBody
      (site: String, username: String)
      : String =
      s"This address is no longer the email address of your account " +
        s"\"$username\" on $site, and links to reset its password won't be " +
        "sent here any more.\n\nIf you made this change, there's nothing to " +
        "do. If you didn't, sign in and change your password at once, or " +
        "regain the account with a recovery code.\n"

    private def within(hours: Int): String =
      if hours == 1 then "the next hour" else s"$hours hours"
