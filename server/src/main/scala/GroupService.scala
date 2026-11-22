package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{
  Group, GroupDetails, Invitation, Invitee, JoinableGroup, ManagedGroup,
  Membership, Principal, User,
}

/**
  * A service managing a signed-in user's groups, memberships, invitations,
  * requests to join and invite links, independent of how it is served.
  *
  * @param groups
  *   The store of groups.
  *
  * @param report
  *   The handler of failures whose detail must not reach the client. The
  *   default prints to the console.
  *
  * @param affected
  *   The hook told whom each committed change concerns (see [[Affected]]).
  */
final class GroupService
  (
    groups: GroupStore,
    report: Throwable => IO[Unit] = error => Console[IO].printStackTrace(error),
    affected: Affected => IO[Unit] = _ => IO.unit,
  ):

  private val failures = Failures(report)

  /**
    * Lists the groups a user manages, with their members, invitees and
    * applicants.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the groups, or a refusal.
    */
  def list(user: User): Answer[List[ManagedGroup]] =
    failures.attempt(groups.managed(user.id))

  /**
    * Lists the principals a user acts as: themselves, every group they belong
    * to, and the system if they act for it.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the principals, or a refusal.
    */
  def principals(user: User): Answer[List[Principal]] =
    failures.attempt(groups.principalsOf(user.id))

  /**
    * Lists the groups a user is a member of.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the memberships, or a refusal.
    */
  def mine(user: User): Answer[List[Membership]] =
    failures.attempt(groups.memberships(user.id))

  /**
    * Lists the groups a user may ask to join.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the groups, or a refusal.
    */
  def joinable(user: User): Answer[List[JoinableGroup]] =
    failures.attempt(groups.joinable(user.id))

  /**
    * Takes a user out of a group.
    *
    * @param user
    *   The signed-in user.
    *
    * @param group
    *   The identifier of the group.
    */
  def leave(user: User, group: Long): Answer[Unit] =
    ownBehalf(user, group)(groups.leave)

  /**
    * Makes a user a member of a group they may change.
    *
    * @param user
    *   The signed-in user.
    *
    * @param group
    *   The identifier of the group.
    */
  def join(user: User, group: Long): Answer[Unit] =
    ownBehalf(user, group)(groups.join)

  /**
    * Asks for a user to join a group they may see.
    *
    * @param user
    *   The signed-in user.
    *
    * @param group
    *   The identifier of the group.
    */
  def request(user: User, group: Long): Answer[Unit] =
    ownBehalf(user, group)(groups.request)

  /**
    * Withdraws a user's request to join a group.
    *
    * @param user
    *   The signed-in user.
    *
    * @param group
    *   The identifier of the group.
    */
  def withdraw(user: User, group: Long): Answer[Unit] =
    ownBehalf(user, group)(groups.withdraw)

  /**
    * Gives a group an invite link unless it has one.
    *
    * @param user
    *   The signed-in user, who must be able to change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @return
    *   An answer with the link's code, or a refusal.
    */
  def link(user: User, group: Long): Answer[String] =
    ownBehalf(user, group)(groups.link)

  /**
    * Replaces a group's invite link with one of a new code.
    *
    * @param user
    *   The signed-in user, who must be able to change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @return
    *   An answer with the new code, or a refusal.
    */
  def relink(user: User, group: Long): Answer[String] =
    ownBehalf(user, group)(groups.relink)

  /**
    * Turns off a group's invite link.
    *
    * @param user
    *   The signed-in user, who must be able to change the group.
    *
    * @param group
    *   The identifier of the group.
    */
  def unlink(user: User, group: Long): Answer[Unit] =
    ownBehalf(user, group)(groups.unlink)

  /**
    * Creates a group owned by a user.
    *
    * @param user
    *   The signed-in user.
    *
    * @param details
    *   The group's name and parent.
    *
    * @return
    *   An answer with the stored group, or a refusal.
    */
  def create(user: User, details: GroupDetails): Answer[Group] = named(
    details.name,
  )(reshaping(IO.pure(Seq.empty))(groups.create(user.id, details))(created =>
    Seq(created.id),
  ))

  /**
    * Renames or moves a group.
    *
    * @param user
    *   The signed-in user, who must be able to change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param details
    *   The group's new name and parent.
    */
  def update
    (
      user: User,
      group: Long,
      details: GroupDetails,
    )
    : Answer[Unit] = named(details.name)(reshapingOne(group)(
    groups.update(user.id, group, details),
  ))

  /**
    * Deletes a group and every group nested beneath it that the user owns too,
    * passing whatever they alone owned to the user, whose new holders are told.
    *
    * @param user
    *   The signed-in user, who must own the group.
    *
    * @param group
    *   The identifier of the group.
    */
  def delete(user: User, group: Long): Answer[Unit] = failures.attempt(
    reshaping(groups.subtreeOf(user.id, group))(groups.delete(user.id, group))(
      _ => Seq.empty,
    ).flatMap(_.traverse_((resource, formerly) =>
      tell(groups.grantsChanged(resource, formerly)),
    )),
  )

  /**
    * Makes a group public or private.
    *
    * @param user
    *   The signed-in user, who must be able to change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param public
    *   Whether the group is to be public.
    */
  def setPublic(user: User, group: Long, public: Boolean): Answer[Unit] =
    failures.attempt(
      reshapingOne(group)(groups.setPublic(user.id, group, public)),
    )

  /**
    * Invites a user, named by username, to a group, reporting the invitation
    * sent as [[Affected.Invited]] beside the change to the group.
    *
    * @param user
    *   The signed-in user, who must be able to change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param invitee
    *   The user to invite, by username.
    *
    * @return
    *   An answer with the invitee, or a refusal.
    */
  def invite(user: User, group: Long, invitee: Invitee): Answer[User] = named(
    invitee.username,
  )(
    groups
      .invite(user.id, group, invitee.username.trim)
      .flatTap(offer =>
        tell(membersChanged(Seq(group), offer.invitee.id)) *>
          offer
            .invitation
            .traverse_(sent =>
              tell(IO.pure(Affected.Invited(sent, group, offer.invitee.id))),
            ),
      )
      .map(_.invitee),
  )

  /**
    * Admits someone who asked to join a group.
    *
    * @param user
    *   The signed-in user, who must be able to change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param person
    *   The identifier of the applicant.
    */
  def admit(user: User, group: Long, person: Long): Answer[Unit] = failures
    .attempt(
      changingMembers(Seq(group), person)(groups.admit(user.id, group, person)),
    )

  /**
    * Removes a member, invitee or applicant from a group.
    *
    * @param user
    *   The signed-in user, who must be able to change the group.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param person
    *   The identifier of the person to remove.
    */
  def remove(user: User, group: Long, person: Long): Answer[Unit] = failures
    .attempt(
      changingMembers(Seq(group), person)(groups.remove(user.id, group, person)),
    )

  /**
    * Lists a user's unanswered invitations.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the invitations, or a refusal.
    */
  def invitations(user: User): Answer[List[Invitation]] =
    failures.attempt(groups.invitations(user.id))

  /**
    * Accepts one of a user's invitations, joining its group, or one sent to
    * someone whose account they may change, for them.
    *
    * @param user
    *   The signed-in user.
    *
    * @param invitation
    *   The identifier of the invitation.
    */
  def accept(user: User, invitation: Long): Answer[Unit] =
    answering(user, invitation)(groups.accept)

  /**
    * Declines one of a user's invitations, deleting it, or one sent to someone
    * whose account they may change, for them.
    *
    * @param user
    *   The signed-in user.
    *
    * @param invitation
    *   The identifier of the invitation.
    */
  def decline(user: User, invitation: Long): Answer[Unit] =
    answering(user, invitation)(groups.decline)

  /**
    * Runs a change to who is in, invited to or asking to join some groups, then
    * tells their managers, their members and the person concerned.
    */
  private def changingMembers[X]
    (changed: Seq[Long], person: Long)
    (change: IO[X])
    : IO[X] = change.flatTap(_ => tell(membersChanged(changed, person)))

  private def membersChanged(changed: Seq[Long], person: Long): IO[Affected] =
    groups.membersChanged(changed, person)

  /**
    * Runs a change to groups' names, nesting, visibility or existence, then
    * tells everyone who saw them before or sees them after.
    */
  private def reshaping[X]
    (before: IO[Seq[Long]])
    (change: IO[X])
    (after: X => Seq[Long])
    : IO[X] =
    for
      earlier <- before.flatMap(groups.surroundings)
      result  <- change
      _       <- tell(
        groups
          .surroundings(after(result))
          .map(later =>
            Affected.Groups(
              if earlier.public || later.public then Audience.Everyone
              else Audience.People(earlier.people ++ later.people),
              earlier.enclosing ++ later.enclosing,
            ),
          ),
      )
    yield result

  private def reshapingOne[X](group: Long)(change: IO[X]): IO[X] =
    reshaping(IO.pure(Seq(group)))(change)(_ => Seq(group))

  /** Tells the host whom a committed change concerns, reporting any failure. */
  private def tell(concerned: IO[Affected]): IO[Unit] = concerned
    .flatMap(affected)
    .handleErrorWith(report)

  private def ownBehalf[X]
    (user: User, group: Long)
    (change: (Long, Long) => IO[X])
    : Answer[X] = failures.attempt(
    changingMembers(Seq(group), user.id)(change(user.id, group)),
  )

  /**
    * Answers an invitation the user may answer, for themselves or for someone
    * whose account they may change, then tells whom it concerns.
    */
  private def answering
    (user: User, invitation: Long)
    (answer: (Long, Long) => IO[Unit])
    : Answer[Unit] = failures.attempt(
    groups
      .answerable(user.id, invitation)
      .flatMap:
        case Some((group, invitee)) =>
          changingMembers(Seq(group), invitee)(answer(user.id, invitation))
        case None => answer(user.id, invitation),
  )

  private def named[X](name: String)(change: => IO[X]): Answer[X] = failures
    .attemptRefusable(checked(Bounds.name(name))(change.map(Right(_))))
