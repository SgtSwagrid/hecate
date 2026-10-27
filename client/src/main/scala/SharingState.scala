package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.model.{Access, Grant, InviteLink, Principal}
import com.raquo.laminar.api.L.*
import io.circe.{Decoder, Json}
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * A browser-side view of who holds one resource and its invite link, driving
  * the endpoints of `SharingApi` for its owner. Nothing here subscribes on its
  * own, so the view that binds it starts and stops every request.
  *
  * @param auth
  *   The sign-in state, rechecked on a refusal.
  *
  * @param segment
  *   The path segment of the resource's kind, as in `/api/{segment}/{id}`.
  *
  * @param id
  *   The identifier of the resource.
  *
  * @param reloads
  *   The stream emitting whenever the resource may have changed elsewhere.
  *
  * @param linked
  *   Whether the resource has an invite link among these endpoints. A group's
  *   is kept with the group instead.
  */
final class SharingState
  (
    auth: AuthState,
    segment: String,
    id: Long,
    reloads: EventStream[Unit] = EventStream.empty,
    linked: Boolean = true,
  ):

  private val regrants = new EventBus[Unit]

  private val relinks = new EventBus[Unit]

  private val failures = new EventBus[Option[String]]

  /** The grants over the resource, read again after each change. */
  val grants: Signal[List[Grant]] = read[List[Grant]](base, regrants.events)
    .startWith(List.empty)

  /** The principals holding any access, for a checklist of recipients. */
  val holders: Signal[Set[Principal]] = grants.map(_.map(_.principal).toSet)

  /** The resource's invite link, if any, read again after each change. */
  val link: Signal[Option[InviteLink]] =
    if linked then
      read[Option[InviteLink]](linkBase, relinks.events).startWith(None)
    else Val(None)

  /** Why the last change was refused, if it was. */
  val error: Signal[Option[String]] = failures.events.startWith(None)

  /**
    * The access one principal holds, if any.
    *
    * @param principal
    *   The user or group.
    *
    * @return
    *   A signal of the access they hold.
    */
  def accessOf(principal: Principal): Signal[Option[Access]] =
    grants.map(_.find(_.principal == principal).map(_.access))

  /**
    * Grants a principal exactly the given access, or withdraws theirs, with any
    * refusal reported through [[error]].
    *
    * @param principal
    *   The user or group.
    *
    * @param access
    *   The access they are to hold, or `None` for none.
    *
    * @return
    *   A stream making the change when followed, emitting once it is done.
    */
  def share
    (
      principal: Principal,
      access: Option[Access],
    )
    : EventStream[Unit] =
    val url     = s"$base/${ principal.kind }/${ principal.id }"
    val request = access match
      case Some(level) => Fetch.put(url, body = level)
      case None        => Fetch.delete(url)
    changing[Unit](request, regrants)

  /**
    * Changes the invite link, with any refusal reported through [[error]].
    *
    * @param change
    *   The change to make.
    *
    * @return
    *   A stream making the change when followed, emitting once it is done.
    */
  def relink(change: SharingState.LinkChange): EventStream[Unit] =
    val request = change match
      case SharingState.LinkChange.Grant(access) =>
        Fetch.put(linkBase, body = access)
      case SharingState.LinkChange.Renew => Fetch.post(linkBase)
      case SharingState.LinkChange.Off   => Fetch.delete(linkBase)
    changing[Json](request, relinks)

  /**
    * Reads an endpoint at first and on each reread or reload, silent if
    * refused.
    */
  private def read[X : Decoder]
    (url: String, again: EventStream[Unit])
    : EventStream[X] = EventStream
    .merge(
      EventStream.fromValue(()),
      again,
      reloads,
    )
    .flatMapSwitch(_ =>
      auth.explained[X](Fetch.get(url)).collect { case Right(value) => value },
    )

  /** Makes a change, reports any refusal, and has what it changed reread. */
  private def changing[X : Decoder]
    (
      request: EventStream[Response],
      reread: EventBus[Unit],
    )
    : EventStream[Unit] = auth
    .explained[X](request)
    .map: outcome =>
      failures.emit(outcome.left.toOption)
      reread.emit(())

  private def base: String = s"/api/$segment/$id/grants"

  private def linkBase: String = s"/api/$segment/$id/invite-link"

object SharingState:

  /** A change to a resource's invite link. */
  enum LinkChange:

    /**
      * Makes a link granting the given access, or makes the link grant it.
      *
      * @param access
      *   The access the link is to grant.
      */
    case Grant(access: Access)

    /** Gives the link a new code, ending the old. */
    case Renew

    /** Turns the link off. */
    case Off
