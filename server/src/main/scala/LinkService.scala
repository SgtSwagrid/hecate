package com.alecdorrington.hecate
package server

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{
  AuthRefusal, Grant, InviteCode, LinkPreview, LinkTarget, Principal, Resource,
  User,
}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import slick.dbio.DBIO

/**
  * A service that previews and follows invite links. A link to a group joins
  * it; a link to a resource grants its access, never lowering what the follower
  * holds. The host makes links to its resources through [[LinkStore]].
  *
  * Codes are short enough to guess, so a user who tries too many that lead
  * nowhere within `window` finds every code leads nowhere until it passes.
  * Strangers following links as guests share one allowance, and once it is
  * spent are told guests are paused. The counts are kept in memory.
  *
  * @param links
  *   The store of links.
  *
  * @param groups
  *   The store of groups.
  *
  * @param grants
  *   The store of grants.
  *
  * @param db
  *   The database the stores use.
  *
  * @param resources
  *   The lookup of a resource's name that locks its row until the transaction
  *   ends, yielding `None` for a missing resource. It runs in the transaction
  *   following a link, so the access granted cannot outlive the resource (see
  *   [[GrantStore.revokeOver]]). The default knows no resources.
  *
  * @param report
  *   The handler of failures whose detail must not reach the client.
  *
  * @param guesses
  *   The number of codes leading nowhere one user may try within `window`.
  *
  * @param window
  *   The time a code that led nowhere counts against its tryer.
  *
  * @param affected
  *   The hook told whom a followed link concerns (see [[Affected]]).
  *
  * @param guests
  *   The service that makes guests, letting strangers follow links as one, or
  *   `None`. Its [[AuthPolicy.guests]] must allow them too.
  *
  * @param strangerGuesses
  *   The number of codes leading nowhere all guests together may try within
  *   `window`.
  */
final class LinkService
  (
    links: LinkStore,
    groups: GroupStore,
    grants: GrantStore,
    db: Transactor,
    resources: Resource => DBIO[Option[String]] = _ => DBIO.successful(None),
    report: Throwable => IO[Unit] = error => Console[IO].printStackTrace(error),
    guesses: Int = 20,
    window: FiniteDuration = 1.hour,
    affected: Affected => IO[Unit] = _ => IO.unit,
    guests: Option[AuthService] = None,
    strangerGuesses: Int = 100,
  ):

  private val failures = Failures(report)

  /**
    * The times, in milliseconds since the epoch, that each user lately tried a
    * code leading nowhere; strangers are under `None`.
    */
  private val misses = Ref.unsafe[IO, Map[Option[Long], List[Long]]](Map.empty)

  private val permissions = Permissions(groups, grants)

  /**
    * Shows a user where an invite link leads before they follow it.
    *
    * @param user
    *   The signed-in user.
    *
    * @param code
    *   The link's code, in any case.
    *
    * @return
    *   An answer with the preview, or a refusal.
    */
  def preview(user: User, code: String): Answer[LinkPreview] =
    failures.attempt(glance(user.id, code))

  /**
    * Follows an invite link, giving the user what it leads to. Following it
    * again changes nothing.
    *
    * @param user
    *   The signed-in user.
    *
    * @param code
    *   The link's code, in any case.
    *
    * @return
    *   An answer with the link's target, or a refusal.
    */
  def follow(user: User, code: String): Answer[LinkTarget] =
    failures.attempt(take(user.id, code))

  /** Whether strangers may follow links as guests here. */
  def welcoming: Boolean = guests.exists(_.rules.guests)

  /**
    * Follows an invite link as a new guest: makes the guest, gives them what
    * the link leads to and signs them in, all at once, so a link leading
    * nowhere makes nobody. Refused unless [[welcoming]].
    *
    * @param code
    *   The link's code, in any case.
    *
    * @param name
    *   The name the guest gave, which becomes their username.
    *
    * @return
    *   An answer with the guest, the link's target and the session's cookie, or
    *   a refusal.
    */
  def welcome
    (code: String, name: String)
    : Answer[(User, LinkTarget, SessionCookie)] = guests
    .filter(_.rules.guests)
    .fold(IO.pure(Left(AuthRefusal.NoGuests)))(auth =>
      failures.attemptRefusable(
        for
          (target, _) <- found(None, code)
          made <- auth.createGuest(name)(guest => give(guest.id, code, target))
          _    <- made.traverse_((guest, _, _) => tell(guest.id, target))
        yield made.map((guest, _, session) => (guest, target, session)),
      ),
    )

  private def glance(user: Long, code: String): IO[LinkPreview] =
    for
      (target, inviter) <- found(Some(user), code)
      (name, member)    <- db.run(named(user, target))
      held              <- has(user, target)
    yield LinkPreview(target, name, inviter, member || held)

  /** The target's name, and whether the user is a member of it if a group. */
  private def named(user: Long, target: LinkTarget): DBIO[(String, Boolean)] =
    target match
      case LinkTarget.Joining(group) =>
        groups.glance(user, group).flatMap(present(_))
      case LinkTarget.Sharing(resource, _) =>
        resources(resource).flatMap(present(_)).map(_ -> false)

  private def has(user: Long, target: LinkTarget): IO[Boolean] = target match
    case LinkTarget.Joining(_)                => IO.pure(false)
    case LinkTarget.Sharing(resource, access) =>
      permissions.access(user, resource).map(_.exists(_.includes(access)))

  private def take(user: Long, code: String): IO[LinkTarget] =
    for
      (target, _) <- found(Some(user), code)
      _           <- db.run(links.atomically(give(user, code, target)))
      _           <- tell(user, target)
    yield target

  /** Tells the host whom a followed link concerns, reporting any failure. */
  private def tell(user: Long, target: LinkTarget): IO[Unit] = (target match
    case LinkTarget.Joining(group) => groups.membersChanged(Seq(group), user)
    case LinkTarget.Sharing(resource, _) =>
      groups.grantsChanged(resource, Set.empty)
  ).flatMap(affected).handleErrorWith(report)

  /**
    * Gives the user what a link leads to, rereading the link under its target's
    * lock.
    */
  private def give
    (
      user: Long,
      code: String,
      target: LinkTarget,
    )
    : DBIO[Unit] = target match
    case LinkTarget.Joining(group) => groups.follow(user, group, code)
    case LinkTarget.Sharing(resource, access) =>
      for
        _     <- resources(resource).flatMap(present(_))
        again <- links.find(code)
        _     <-
          if again.flatMap(_._1.target).contains(target) then
            grants.raise(Grant(
              resource,
              Principal.Person(user),
              access,
            ))
          else DBIO.failed(AuthProblem(AuthRefusal.LinkMissing))
      yield ()

  /**
    * Finds a link's target and creator, unless the asker (`None` for strangers)
    * has spent their allowance of misses. A miss counts against the asker.
    */
  private def found(who: Option[Long], code: String): IO[(LinkTarget, User)] =
    for
      now    <- IO.realTime.map(_.toMillis)
      recent <- misses.modify(LinkService.recent(who, now - window.toMillis))
      blocked = recent >= allowance(who)
      _ <- IO.raiseWhen(who.isEmpty && blocked)(AuthProblem(
        AuthRefusal.GuestsPaused,
      ))
      link <-
        if blocked || code.length > InviteCode.length then IO.pure(None)
        else db.run(links.find(code))
      leading <- link
        .flatMap((row, inviter) => row.target.map(_ -> inviter))
        .fold(missed(who, now))(IO.pure)
    yield leading

  private def missed(who: Option[Long], now: Long): IO[Nothing] = misses
    .update(tried =>
      tried.updated(
        who,
        (now :: tried.getOrElse(who, Nil)).take(allowance(who)),
      ),
    )
    .flatMap(_ => IO.raiseError(AuthProblem(AuthRefusal.LinkMissing)))

  private def allowance(who: Option[Long]): Int =
    if who.isDefined then guesses else strangerGuesses

  private def present[X](found: Option[X]): DBIO[X] =
    required(found, AuthRefusal.LinkMissing)

object LinkService:

  /**
    * Counts the asker's misses since a moment, forgetting earlier ones and
    * dropping anyone left with none.
    */
  private def recent
    (who: Option[Long], since: Long)
    (tried: Map[Option[Long], List[Long]])
    : (Map[Option[Long], List[Long]], Int) =
    val kept = tried.getOrElse(who, Nil).filter(_ > since)
    (if kept.isEmpty then tried - who else tried.updated(who, kept), kept.size)
