package com.alecdorrington.hecate
package client

import com.raquo.laminar.api.L.*
import io.circe.Encoder
import io.circe.syntax.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/**
  * A server's reply to a request.
  *
  * @param status
  *   The reply's HTTP status.
  *
  * @param data
  *   The reply's body, as text.
  */
final case class Response(status: Int, data: String)

/**
  * Requests to the server, each a stream that sends it when started, and again
  * each time it is started anew, and emits its one reply, or an error if the
  * reply never comes. A body is sent as JSON, and the session's cookie with
  * each. Losing its last subscriber aborts the request, unless it is kept alive
  * (`put`'s `keepalive`), which is left to finish as the page closes; a reply
  * reaches only the run that sent it.
  */
object Fetch:

  /**
    * Gets a resource.
    *
    * @param url
    *   The resource's address.
    *
    * @return
    *   A stream of the reply.
    */
  def get(url: String): EventStream[Response] =
    send(dom.HttpMethod.GET, url, None)

  /**
    * Deletes a resource.
    *
    * @param url
    *   The resource's address.
    *
    * @return
    *   A stream of the reply.
    */
  def delete(url: String): EventStream[Response] =
    send(dom.HttpMethod.DELETE, url, None)

  /**
    * Posts to a resource, with no body.
    *
    * @param url
    *   The resource's address.
    *
    * @return
    *   A stream of the reply.
    */
  def post(url: String): EventStream[Response] =
    send(dom.HttpMethod.POST, url, None)

  /**
    * Posts a value to a resource, as JSON.
    *
    * @param url
    *   The resource's address.
    *
    * @param body
    *   The value posted.
    *
    * @tparam X
    *   The value's type.
    *
    * @return
    *   A stream of the reply.
    */
  def post[X : Encoder](url: String, body: X): EventStream[Response] = send(
    dom.HttpMethod.POST,
    url,
    Some(body.asJson.noSpaces),
  )

  /**
    * Puts a resource, with no body.
    *
    * @param url
    *   The resource's address.
    *
    * @return
    *   A stream of the reply.
    */
  def put(url: String): EventStream[Response] =
    send(dom.HttpMethod.PUT, url, None)

  /**
    * Puts a value as a resource, as JSON.
    *
    * @param url
    *   The resource's address.
    *
    * @param body
    *   The value put.
    *
    * @param keepalive
    *   Whether the request may outlive the page, as one sent as it closes must,
    *   which the browser allows only for a small body.
    *
    * @tparam X
    *   The value's type.
    *
    * @return
    *   A stream of the reply.
    */
  def put[X : Encoder]
    (
      url: String,
      body: X,
      keepalive: Boolean = false,
    )
    : EventStream[Response] = send(
    dom.HttpMethod.PUT,
    url,
    Some(body.asJson.noSpaces),
    keepalive,
  )

  private def send
    (
      method: dom.HttpMethod,
      url: String,
      body: Option[String],
      keepalive: Boolean = false,
    )
    : EventStream[Response] =
    var inFlight = Option.empty[dom.AbortController]
    EventStream.fromCustomSource[Response](
      start = (fire, fail, _, _) =>
        val controller = new dom.AbortController
        inFlight = Some(controller)
        val reply = dom
          .fetch(
            url,
            init(method, body, keepalive, controller),
          )
          .toFuture
          .flatMap(response =>
            response.text().toFuture.map(Response(response.status, _)),
          )
        // Only into the run that sent it: a stream stopped and started again
        // has a controller of its run's own.
        def current = inFlight.contains(controller)
        reply.foreach(answer => if current then fire(answer))
        reply
          .failed
          .foreach(error =>
            if current && !controller.signal.aborted then fail(error),
          )
      ,
      // A request kept alive is left to finish, as the page closing stops it.
      stop = _ =>
        if !keepalive then inFlight.foreach(_.abort())
        inFlight = None,
    )

  private def init
    (
      verb: dom.HttpMethod,
      json: Option[String],
      outlives: Boolean,
      controller: dom.AbortController,
    )
    : dom.RequestInit = new dom.RequestInit:
    method = verb
    credentials = dom.RequestCredentials.`same-origin`
    keepalive = outlives
    signal = controller.signal
    json.foreach: text =>
      headers = js.Dictionary("Content-Type" -> "application/json")
      body = text
