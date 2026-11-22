package com.alecdorrington.hecate
package client

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*

/**
  * An outcome of one request. The three must not be conflated: a refusal tells
  * the user something, and an unreachable server tells nothing.
  */
enum Outcome:

  /**
    * The server answered without refusing.
    *
    * @param response
    *   The reply.
    */
  case Answered(response: Response)

  /**
    * The server refused.
    *
    * @param reason
    *   The refusal, worded for the reader.
    */
  case Refused(reason: String)

  /**
    * The request never reached the server, so nothing is known: the user is no
    * more signed out or refused than before.
    *
    * @param detail
    *   The browser's message, not meant for a reader.
    */
  case Unreachable(detail: String)

object Outcome:

  /**
    * Reads the outcome of a request.
    *
    * @param request
    *   The stream of the request's one reply.
    *
    * @return
    *   A stream of its one outcome.
    */
  def of(request: EventStream[Response]): EventStream[Outcome] = request
    .map(read)
    .recover { case failure => Some(Unreachable(failure.getMessage)) }

  /**
    * Reads the outcome of a request, but for the refusals its caller answers
    * itself, which are left to it unread.
    *
    * @param request
    *   The stream of the request's one reply.
    *
    * @param answers
    *   Whether the caller answers a refusal itself.
    *
    * @return
    *   A stream of its one outcome, or of the refusal left to the caller.
    */
  def unless
    (
      request: EventStream[Response],
      answers: Response => Boolean,
    )
    : EventStream[Either[Response, Outcome]] = request
    .map(response =>
      if response.status >= 400 && answers(response) then Left(response)
      else Right(read(response)),
    )
    .recover { case failure => Some(Right(Unreachable(failure.getMessage))) }

  private def read(response: Response): Outcome =
    if response.status >= 400 then Refused(response.data)
    else Answered(response)

  private[client] def tracked[X]
    (
      outcome: EventStream[X],
      pending: Var[Boolean],
    )
    (handle: X => Unit)
    : Unit =
    pending.set(true)
    once(outcome): outcome =>
      pending.set(false)
      handle(outcome)

  /**
    * Lets go of the stream after its first event, which a state outliving every
    * view would otherwise keep subscribed while the page is open.
    */
  private[client] def once[X](stream: EventStream[X])(handle: X => Unit): Unit =
    val owner   = ManualOwner()
    given Owner = owner
    stream.foreach: event =>
      handle(event)
      owner.killSubscriptions()
