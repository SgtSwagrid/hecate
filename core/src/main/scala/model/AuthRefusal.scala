package com.alecdorrington.hecate
package model

/**
  * A reason this library refuses a request, as a value that the host's
  * [[com.alecdorrington.hecate.i18n.Wording]] phrases in the reader's language.
  */
enum AuthRefusal:

  /** An endpoint that needs a signed-in user was called without one. */
  case SignedOut

  /** A registration asked for a username that is taken. */
  case UsernameTaken

  /**
    * A sign-in failed. It never says whether the username or the password was
    * wrong, so that it cannot be used to find valid usernames.
    */
  case CredentialsIncorrect

  /** A password given again to confirm a request is wrong. */
  case PasswordIncorrect

  /**
    * A recovery failed. It never says which part was wrong, as for
    * [[CredentialsIncorrect]].
    */
  case RecoveryIncorrect

  /** The host offers no account deletion. */
  case NoAccountDeletion

  /** A registration asked for a blank username. */
  case UsernameEmpty

  /** Someone asked to follow an invite link as a guest without giving a name. */
  case GuestNameEmpty

  /** The host lets nobody follow an invite link as a guest. */
  case NoGuests

  /**
    * No link may be followed as a guest for a while, as guests lately tried too
    * many codes that lead nowhere. Every code is refused alike meanwhile, so
    * this discloses nothing about the code tried.
    */
  case GuestsPaused

  /** An account to be claimed has a password; only a guest's can be claimed. */
  case NotGuest

  /**
    * A password is shorter than the policy allows.
    *
    * @param min
    *   The fewest characters a password may have.
    */
  case PasswordTooShort(min: Int)

  /**
    * A name, code or password is longer than this library stores or compares.
    *
    * @param max
    *   The most characters it may have.
    */
  case TooLong(max: Int)

  /**
    * An invitation named a username that nobody has.
    *
    * @param username
    *   The username the invitation named.
    */
  case UserMissing(username: String)

  /**
    * A request named a group that does not exist, or one the caller may not act
    * on. One reason for both, to disclose nothing about other users' groups.
    */
  case GroupMissing

  /**
    * A request named an invitation that does not exist, or that was sent to
    * somebody else. One reason for both, as for [[GroupMissing]].
    */
  case InvitationMissing

  /**
    * An owner admitted someone who has not asked to join the group, or who has
    * withdrawn their request.
    */
  case RequestMissing

  /**
    * An invite link was opened whose code leads nowhere: it never existed, or
    * was replaced or turned off. One reason for all three, as for
    * [[GroupMissing]].
    */
  case LinkMissing

  /** A group was to be nested inside a parent that does not exist. */
  case ParentGroupMissing

  /** A group was to be nested inside itself, or a group inside it. */
  case GroupInsideItself

  /**
    * An account cannot be deleted while it is the only owner of something.
    *
    * @param count
    *   The number of resources the account alone owns.
    */
  case SoleOwner(count: Int)

  /**
    * A request named a resource that does not exist or that the caller may not
    * see, alike so that identifiers cannot be probed.
    *
    * @param kind
    *   The kind of the resource, as the host names it.
    */
  case ResourceMissing(kind: String)

  /**
    * Someone who may see a resource but does not own it asked to see or change
    * who holds it.
    *
    * @param kind
    *   The kind of the resource, as the host names it.
    */
  case NotOwner(kind: String)

  /**
    * A change would leave a resource with nobody holding [[Access.Own]].
    *
    * @param kind
    *   The kind of the resource, as the host names it.
    */
  case LastOwner(kind: String)

  /** An invite link was to grant [[Access.Own]], which only naming someone may. */
  case OwnershipByLink

  /**
    * A grant named a user or group that does not exist or that the caller may
    * not address, alike as for [[GroupMissing]].
    */
  case PrincipalMissing

  /** A given email address is not valid. */
  case EmailInvalid

  /**
    * A link sent by email has expired, been used, or never existed. One reason
    * for all three, to disclose nothing about links sent to anybody else.
    */
  case EmailLinkMissing

  /** The host sends no email. */
  case NoEmail

  /** A user asked for an email too soon after the last one sent to them. */
  case MailedRecently

  /**
    * Someone tried to sign in to, or regain, an account its managers have
    * suspended. Said only once the password, code or link given is right.
    */
  case Suspended

  /**
    * A user asked to do to their own account what only its managers may, such
    * as deleting it.
    */
  case AccountManaged

  /**
    * Someone asked to manage their own account, or to grant a user access over
    * their own account: their powers over it come from being signed in as it,
    * never from a grant.
    */
  case ManagingSelf

  /**
    * Accounts to be created named usernames that are taken, or named one
    * username twice, whatever its letter case.
    *
    * @param usernames
    *   The usernames taken or repeated, as given.
    */
  case UsernamesTaken(usernames: List[String])

  /**
    * More accounts were to be created at once than the library allows.
    *
    * @param max
    *   The most accounts one request may create.
    */
  case TooManyAccounts(max: Int)

  /**
    * A user asked to create more accounts within an hour than the host allows.
    *
    * @param perHour
    *   The most accounts one user may create in an hour.
    */
  case AccountsCreatedRecently(perHour: Int)

  /**
    * A guest asked to do what only someone with a username and password may,
    * such as creating accounts for others.
    */
  case GuestAccount

  /**
    * Someone signed in to an account whose password someone else set, which its
    * user must replace with their own first. Said only once the password is
    * right.
    */
  case PasswordChangeRequired

  /** A password to replace one someone else set was the same password. */
  case PasswordReused

  /** A request failed for an internal reason, whose detail stays on the server. */
  case Failed
