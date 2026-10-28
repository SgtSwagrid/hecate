package com.alecdorrington.hecate
package client

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

class FetchSuite extends FunSuite:

  import FetchSuite.*

  private val original = js.Dynamic.global.fetch

  override def afterEach(context: AfterEach): Unit = js
    .Dynamic
    .global
    .updateDynamic("fetch")(original)

  test("A body is sent as JSON, with the session's cookie"):
    val stub  = Stub.installed()
    val reply = first(Fetch.put("/api/things", List(1, 2)))
    stub.answer(200, "done")
    reply.map: response =>
      assertEquals(response, Response(200, "done"))
      assertEquals(stub.urls.toList, List("/api/things"))
      val sent = stub.sent(0)
      assertEquals(
        sent.method.asInstanceOf[String],
        "PUT",
      )
      assertEquals(
        sent.body.asInstanceOf[String],
        "[1,2]",
      )
      assertEquals(
        sent.headers.selectDynamic("Content-Type").asInstanceOf[String],
        "application/json",
      )
      assertEquals(
        sent.credentials.asInstanceOf[String],
        "same-origin",
      )

  test("A refusal is read as refused, and no answer as unreachable"):
    val stub    = Stub.installed()
    val refused = first(Outcome.of(Fetch.get("/api/missing")))
    stub.answer(404, "No such thing.")
    val unreachable = first(Outcome.of(Fetch.post("/api/offline")))
    stub.fail("The network is down.")
    for
      refusal <- refused
      silence <- unreachable
    yield
      assertEquals(
        refusal,
        Outcome.Refused("No such thing."),
      )
      assert(
        silence.isInstanceOf[Outcome.Unreachable],
        silence,
      )

  test("Stopping aborts a request, but not one kept alive"):
    val stub  = Stub.installed()
    val owner = ManualOwner()
    Fetch.get("/api/plain").addObserver(Observer.empty)(using owner)
    Fetch
      .put("/api/kept", "draft", keepalive = true)
      .addObserver(Observer.empty)(using owner)
    owner.killSubscriptions()
    assert(stub.sent(0).signal.aborted.asInstanceOf[Boolean])
    assert(!stub.sent(1).signal.aborted.asInstanceOf[Boolean])
    assert(stub.sent(1).keepalive.asInstanceOf[Boolean])

  test("A reply reaches only the run that sent it"):
    val stub    = Stub.installed()
    val request = Fetch.put("/api/kept", "draft", keepalive = true)
    val earlier = ManualOwner()
    request.addObserver(Observer.empty)(using earlier)
    earlier.killSubscriptions()
    val later = first(request)
    stub.answer(200, "to the earlier run")
    stub.answer(200, "to the later run")
    later.map(reply =>
      assertEquals(
        reply,
        Response(200, "to the later run"),
      ),
    )

object FetchSuite:

  /**
    * A stand-in for the browser's `fetch`, which records each request and
    * answers them in turn as told.
    */
  final class Stub:

    val urls = mutable.ArrayBuffer.empty[String]
    val sent = mutable.ArrayBuffer.empty[js.Dynamic]

    private val waiting = mutable.Queue.empty[(js.Dynamic, js.Dynamic)]

    private val fetch: js.Function2[String, js.Dynamic, js.Promise[js.Any]] =
      (url, init) =>
        urls += url
        sent += init
        new js.Promise[js.Any]((resolve, reject) =>
          waiting.enqueue(
            resolve.asInstanceOf[js.Dynamic] -> reject.asInstanceOf[js.Dynamic],
          ),
        )

    /** Answers the earliest request still waiting. */
    def answer(status: Int, text: String): Unit =
      val (resolve, _)                           = waiting.dequeue()
      val body: js.Function0[js.Promise[String]] =
        () => js.Promise.resolve[String](text)
      resolve(js.Dynamic.literal(status = status, text = body))

    /** Fails the earliest request still waiting, as a dropped connection. */
    def fail(message: String): Unit =
      val (_, reject) = waiting.dequeue()
      reject(js.Error(message))

  object Stub:

    def installed(): Stub =
      val stub = Stub()
      js.Dynamic.global.updateDynamic("fetch")(stub.fetch)
      stub

  /** The first event or error of a stream, which it is followed until. */
  def first[A](stream: EventStream[A]): Future[A] =
    val owner  = ManualOwner()
    val result = Promise[A]()
    stream.addObserver(Observer.fromTry { outcome =>
      result.tryComplete(outcome)
      owner.killSubscriptions()
    })(using owner)
    result.future
