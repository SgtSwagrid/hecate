package com.alecdorrington.hecate
package server
package tapir

import cats.effect.IO
import com.alecdorrington.hecate.api.Protocol
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  Credentials, EmailChange, EmailConfirmation, EmailStatus, Group, GroupDetails,
  Guest, LinkTarget, ManagedGroup, PasswordReset, PasswordResetRequest, User,
  Welcome,
}
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import slick.dbio.DBIO
import sttp.client3.{basicRequest, Request, Response, UriContext}
import sttp.client3.impl.cats.CatsMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter

class EndpointsSuite extends CatsEffectSuite:

  import EndpointsSuite.*

  test("registering sets the session cookie, which signs in until signing out"):
    served(): call =>
      for
        before  <- call(me)
        refused <-
          call(basicRequest.get(uri"http://test/api/auth/recovery-codes"))
        registered <- call(register("alice"))
        token = cookieOf(registered).get
        found <- call(me.cookie(Protocol.sessionCookie, token))
        out   <- call(
          basicRequest
            .post(uri"http://test/api/auth/sign-out")
            .cookie(Protocol.sessionCookie, token),
        )
        after <- call(me.cookie(Protocol.sessionCookie, token))
      yield
        // Nobody is signed in without the cookie, so nothing needing a user is
        // served.
        assertEquals(before.body, Right(nobody))
        assertEquals(
          refused.body,
          Left(Wording.english.signedOut),
        )
        assertEquals(
          read[User](registered).map(_.username),
          Right("alice"),
        )
        assertEquals(
          read[Option[User]](found).map(_.map(_.username)),
          Right(Some("alice")),
        )
        assertEquals(cookieOf(out), None)
        assertEquals(
          out.unsafeCookies.find(_.name == Protocol.sessionCookie).map(_.value),
          Some(""),
        )
        assertEquals(after.body, Right(nobody))

  test("a refusal is a bad request, worded in the language it asks for"):
    served(wording = bilingual): call =>
      for
        _       <- call(register("alice"))
        english <- call(register("alice"))
        german  <- call(register("alice").cookie(Protocol.languageCookie, "de"))
        long    <- call(register("a" * (Protocol.maxNameLength + 1)))
      yield
        assertEquals(english.code, StatusCode.BadRequest)
        assertEquals(
          english.body,
          Left(Wording.english.usernameTaken),
        )
        assertEquals(
          german.body,
          Left(German.usernameTaken),
        )
        assertEquals(cookieOf(german), None)
        // Text longer than the protocol allows is refused before it is read.
        assertEquals(long.code, StatusCode.BadRequest)

  test("the rules say what the service offers, and nothing else is served"):
    served(): call =>
      for
        rules    <- call(basicRequest.get(uri"http://test/api/auth/rules"))
        deletion <- call(
          basicRequest
            .post(uri"http://test/api/auth/account/delete")
            .body("""{"password":"hunter2222"}"""),
        )
        email   <- call(resetRequest("alice@example.com"))
        welcome <- call(
          basicRequest
            .post(uri"http://test/api/invite-links/abcd1/welcome")
            .body(Guest("Reader").asJson.noSpaces),
        )
      yield
        assertEquals(
          rules.body,
          Right(
            """{"minPasswordLength":8,"accountDeletion":false,"email":false,"guests":false}""",
          ),
        )
        // Account deletion without a store for it, email where the service
        // sends none, and guests where they are not let in.
        assertEquals(
          List(deletion, email, welcome).map(_.code),
          List.fill(3)(StatusCode.NotFound),
        )

  test("a reset link signs in, and asking for one answers alike for anyone"):
    Outbox().flatMap(outbox =>
      served(
        wording = bilingual,
        mailing = Some(Outbox.mailing(outbox)),
      ): call =>
        for
          token   <- call(register("alice")).map(cookieOf(_).get)
          changed <- call(
            basicRequest
              .put(uri"http://test/api/auth/email")
              .cookie(Protocol.sessionCookie, token)
              .body(
                EmailChange(
                  Some("alice@example.com"),
                  "hunter2222",
                ).asJson.noSpaces,
              ),
          )
          confirmation <- outbox.next
          _            <- call(
            basicRequest
              .post(uri"http://test/api/auth/email/confirm")
              .body(
                EmailConfirmation(Outbox.token(confirmation)).asJson.noSpaces,
              ),
          )
          known   <- call(resetRequest("alice@example.com"))
          unknown <- call(resetRequest("bob@example.com"))
          mail    <- outbox.next
          reset   <- call(
            basicRequest
              .post(uri"http://test/api/auth/password/reset")
              .body(
                PasswordReset(Outbox.token(mail), "correct-horse")
                  .asJson
                  .noSpaces,
              ),
          )
          broken <- call(
            basicRequest
              .post(uri"http://test/api/auth/password/reset")
              .cookie(Protocol.languageCookie, "de")
              .body(PasswordReset("nothing", "correct-horse").asJson.noSpaces),
          )
        yield
          assertEquals(
            read[EmailStatus](changed),
            Right(EmailStatus(None, Some("alice@example.com"))),
          )
          assertEquals(
            (known.code, known.body),
            (unknown.code, unknown.body),
          )
          assertEquals(known.code, StatusCode.Ok)
          assertEquals(
            read[User](reset).map(_.username),
            Right("alice"),
          )
          assert(
            cookieOf(reset).isDefined,
            "no session cookie was set",
          )
          // A link that does not work is refused in the reader's language.
          assertEquals(
            (broken.code, broken.body),
            (StatusCode.BadRequest, Left(German.emailLinkMissing)),
          ),
    )

  test("a group is made and listed for its owner, and its link joins it"):
    served(): call =>
      for
        owner  <- call(register("owner")).map(cookieOf(_).get)
        guest  <- call(register("guest")).map(cookieOf(_).get)
        group  <- bookClub(call, owner)
        listed <- call(
          basicRequest
            .get(uri"http://test/api/groups")
            .cookie(Protocol.sessionCookie, owner),
        )
        code     <- inviteLink(call, owner, group)
        followed <- call(
          basicRequest
            .post(uri"http://test/api/invite-links/$code/follow")
            .cookie(Protocol.sessionCookie, guest),
        )
      yield
        assertEquals(
          read[List[ManagedGroup]](listed).map(_.map(_.group.name)),
          Right(List("Book club")),
        )
        assertEquals(
          read[LinkTarget](followed),
          Right(LinkTarget.Joining(group)),
        )

  test("following a link as a guest signs them in, and claiming keeps them"):
    served(guests = true): call =>
      for
        owner    <- call(register("owner")).map(cookieOf(_).get)
        group    <- bookClub(call, owner)
        code     <- inviteLink(call, owner, group)
        welcomed <- call(
          basicRequest
            .post(uri"http://test/api/invite-links/$code/welcome")
            .body(Guest("Reader").asJson.noSpaces),
        )
        guest = cookieOf(welcomed).get
        found   <- call(me.cookie(Protocol.sessionCookie, guest))
        claimed <- call(
          basicRequest
            .post(uri"http://test/api/auth/claim")
            .cookie(Protocol.sessionCookie, guest)
            .body(Credentials("reader", "hunter2222").asJson.noSpaces),
        )
      yield
        assertEquals(
          read[Welcome](welcomed).map(welcome =>
            (welcome.user.username, welcome.user.guest, welcome.target),
          ),
          Right(("Reader", true, LinkTarget.Joining(group))),
        )
        assertEquals(
          read[Option[User]](found).map(_.map(_.guest)),
          Right(Some(true)),
        )
        assertEquals(
          read[User](claimed).map(user => (user.username, user.guest)),
          Right(("reader", false)),
        )
        assert(
          cookieOf(claimed).exists(_ != guest),
          "claiming set no fresh session cookie",
        )

object EndpointsSuite:

  private type SendRequest =
    Request[Either[String, String], Any] => IO[Response[Either[String, String]]]

  private object German extends Wording:

    override val signedOut: String            = "Anmeldung erforderlich."
    override val usernameTaken: String        = "Dieser Name ist vergeben."
    override val credentialsIncorrect: String = "Name oder Passwort falsch."
    override val passwordIncorrect: String    = "Passwort falsch."
    override val recoveryIncorrect: String    = "Name oder Code falsch."
    override val noAccountDeletion: String    = "Konten bleiben bestehen."
    override val usernameEmpty: String        = "Name fehlt."
    override val groupMissing: String         = "Gruppe nicht vorhanden."
    override val invitationMissing: String    = "Einladung nicht vorhanden."
    override val requestMissing: String       = "Anfrage nicht vorhanden."
    override val linkMissing: String          = "Link ungültig."
    override val parentGroupMissing: String   = "Obergruppe nicht vorhanden."
    override val groupInsideItself: String    = "Gruppe in sich selbst."
    override val requestFailed: String        = "Anfrage fehlgeschlagen."
    override val unreadableReply: String      = "Antwort unlesbar."
    override val unreachable: String          = "Server nicht erreichbar."
    override val passwordChanged: String      = "Passwort geändert."
    override val emailInvalid: String         = "Keine E-Mail-Adresse."
    override val emailLinkMissing: String     = "Link abgelaufen."
    override val noEmail: String              = "Kein E-Mail-Versand."
    override val mailedRecently: String       = "Gerade erst gesendet."
    override val resetLinkSent: String        = "Link unterwegs."
    override val emailConfirmed: String       = "Adresse bestätigt."
    override val emailRemoved: String         = "Adresse entfernt."
    override val guestNameEmpty: String       = "Name fehlt."
    override val noGuests: String             = "Keine Gäste."
    override val guestsPaused: String         = "Gäste pausiert."
    override val notGuest: String             = "Kein Gastkonto."
    override val accountClaimed: String       = "Konto eingerichtet."

    override def confirmationSent(address: String): String =
      s"Link an $address gesendet."

    override def resetMailSubject(site: String): String =
      s"Passwort für $site zurücksetzen"

    override def resetMailBody
      (
        site: String,
        username: String,
        link: String,
        hours: Int,
      )
      : String = s"$username: $link ($hours Std.)"

    override def confirmMailSubject(site: String): String =
      s"Adresse für $site bestätigen"

    override def confirmMailBody
      (site: String, link: String, hours: Int)
      : String = s"$link ($hours Std.)"

    override def addressChangedMailSubject(site: String): String =
      s"Adresse für $site geändert"

    override def addressChangedMailBody
      (site: String, username: String)
      : String = s"$username: Adresse geändert."

    override def passwordTooShort(min: Int): String =
      s"Passwort braucht $min Zeichen."

    override def tooLong(max: Int): String = s"Höchstens $max Zeichen."

    override def userMissing(username: String): String =
      s"Niemand heißt \"$username\"."

    override def soleOwner(count: Int): String =
      s"Alleiniger Eigentümer von $count Dingen."

    override def resourceMissing(kind: String): String = "Nicht gefunden."

    override def notOwner(kind: String): String = "Nur Eigentümer."

    override def lastOwner(kind: String): String = "Letzter Eigentümer."

    override val ownershipByLink: String = "Kein Eigentum per Link."

    override def principalMissing: String = "Niemand gefunden."

  /** German where a request asks for it, and English otherwise. */
  private val bilingual: Option[String] => Wording =
    locale => if locale.contains("de") then German else Wording.english

  /** A request registering one account. */
  private def register(username: String) = basicRequest
    .post(uri"http://test/api/auth/register")
    .body(Credentials(username, "hunter2222").asJson.noSpaces)

  private def resetRequest(address: String) = basicRequest
    .post(uri"http://test/api/auth/password/request-reset")
    .body(PasswordResetRequest(address).asJson.noSpaces)

  private def bookClub(call: SendRequest, session: String): IO[Long] = call(
    basicRequest
      .post(uri"http://test/api/groups")
      .cookie(Protocol.sessionCookie, session)
      .body(GroupDetails("Book club").asJson.noSpaces),
  ).map(read[Group](_).toOption.get.id)

  private def inviteLink
    (
      call: SendRequest,
      session: String,
      group: Long,
    )
    : IO[String] = call(
    basicRequest
      .put(uri"http://test/api/groups/$group/invite-link")
      .cookie(Protocol.sessionCookie, session),
  ).map(read[String](_).toOption.get)
  private val me = basicRequest.get(uri"http://test/api/auth/me")

  // Tapir sends an absent optional body as no body at all, not as `null`.
  private val nobody = ""

  private def cookieOf(answer: Response[?]): Option[String] = answer
    .unsafeCookies
    .find(_.name == Protocol.sessionCookie)
    .map(_.value)
    .filter(_.nonEmpty)

  private def read[X : Decoder]
    (answer: Response[Either[String, String]])
    : Either[String, X] = answer
    .body
    .flatMap(decode[X](_).left.map(_.getMessage))

  private def serve(endpoints: List[ServerEndpoint[Any, IO]]): SendRequest =
    val backend = TapirStubInterpreter(
      SttpBackendStub[IO, Any](CatsMonadAsyncError[IO]()),
    ).whenServerEndpointsRunLogic(endpoints).backend()
    request => request.send(backend)

  private def served
    (
      wording: Option[String] => Wording = _ => Wording.english,
      mailing: Option[Mailing] = None,
      guests: Boolean = false,
    )
    (check: SendRequest => IO[Unit])
    : IO[Unit] = TestDb
    .open("endpoints")
    .use: db =>
      val groups = GroupStore(TestDb.tables, db)
      val auth   = AuthService(
        UserStore(TestDb.tables, db),
        Fixtures.cheap.copy(guests = guests),
        mailing = mailing,
      )
      val served = AuthEndpoints(auth, wording)
      val links  = LinkService(
        LinkStore(TestDb.tables),
        groups,
        GrantStore(TestDb.tables, db),
        db,
        _ => DBIO.successful(None),
        guests = Some(auth),
      )
      check(serve(
        served.api ++ GroupEndpoints(GroupService(groups), served).api ++
          LinkEndpoints(links, served).api,
      ))
