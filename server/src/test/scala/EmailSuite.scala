package com.alecdorrington.hecate
package server

import Fixtures.{cheap, signUp}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.alecdorrington.hecate.api.Protocol
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  AuthRefusal, Credentials, EmailChange, EmailConfirmation, EmailStatus,
  PasswordChange, PasswordReset, PasswordResetRequest, User,
}
import munit.CatsEffectSuite
import slick.jdbc.H2Profile.api.*

class EmailSuite extends CatsEffectSuite:

  import EmailSuite.*

  test("an address becomes the account's once the link sent to it is opened"):
    mailing(): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        changed    <- auth.changeTo(alice, Some(" Alice@Example.COM "))
        mail       <- outbox.next
        confirmed  <- auth.confirmFrom(mail)
        status     <- auth.email(alice)
        again      <- auth.confirmFrom(mail)
      yield
        assertEquals(
          changed,
          Right(EmailStatus(None, Some(address))),
        )
        assertEquals(mail.to, address)
        assert(
          mail.subject.contains("Test"),
          mail.subject,
        )
        assert(
          !mail.body.contains("alice\""),
          "the account was named",
        )
        assertEquals(confirmed, Right(()))
        assertEquals(
          status,
          Right(EmailStatus(Some(address), None)),
        )
        assertEquals(
          again,
          Left(AuthRefusal.EmailLinkMissing),
        )

  test("changing the address needs the password, and an address"):
    mailing(): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        wrong      <- auth.changeTo(alice, Some(address), "hunter3333")
        malformed  <- List(
          "alice",
          "alice@example",
          "alice@@example.com",
          "alice,eve@example.com",
          "alice@example.com\nBcc: eve@example.com",
        ).traverse[IO, Either[AuthRefusal, EmailStatus]](text =>
          auth.changeTo(alice, Some(text)),
        )
        long <- auth.changeTo(alice, Some(s"${ "a" * 250 }@example.com"))
        sent <- outbox.rest
      yield
        assertEquals(
          wrong,
          Left(AuthRefusal.PasswordIncorrect),
        )
        malformed.foreach(assertEquals(_, Left(AuthRefusal.EmailInvalid)))
        assertEquals(
          long,
          Left(AuthRefusal.TooLong(Protocol.maxEmailLength)),
        )
        assertEquals(sent, Nil)

  test("a forgotten password is reset through the confirmed address"):
    mailing(): harness =>
      import harness.{auth, outbox}
      for
        (alice, session) <- signUp(auth, "alice")
        _                <- confirmed(harness, alice, address)
        asked            <- auth.requestPasswordReset(
          PasswordResetRequest("ALICE@example.com"),
          None,
        )
        mail  <- outbox.next
        reset <-
          auth.resetPassword(PasswordReset(Outbox.token(mail), "correct-horse"))
        old      <- auth.current(Some(session))
        signedIn <- auth.signIn(Credentials("alice", "correct-horse"))
        again    <- auth.resetPassword(
          PasswordReset(Outbox.token(mail), "battery-staple"),
        )
      yield
        assertEquals(asked, Right(()))
        assertEquals(mail.to, address)
        assert(
          mail.body.contains("\"alice\""),
          mail.body,
        )
        assertEquals(reset.map(_._1), Right(alice))
        assertEquals(old, Right(None))
        assert(
          signedIn.isRight,
          "the new password was refused",
        )
        assertEquals(
          again,
          Left(AuthRefusal.EmailLinkMissing),
        )

  test("every account with the address is sent a link of its own"):
    mailing(): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        (bob, _)   <- signUp(auth, "bob")
        _          <- confirmed(harness, alice, address)
        _          <- confirmed(harness, bob, address)
        _      <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        first  <- outbox.next
        second <- outbox.next
        reset  <- auth.resetPassword(
          PasswordReset(Outbox.token(second), "correct-horse"),
        )
      yield
        assertEquals(
          Set(first.body, second.body).map(_.contains("\"bob\"")),
          Set(true, false),
        )
        assertEquals(
          reset.map(_._1.username),
          Right(if second.body.contains("\"bob\"") then "bob" else "alice"),
        )

  test(
    "an address no account has confirmed is answered alike, and sent nothing",
  ):
    mailing(): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- auth.changeTo(alice, Some(address))
        _          <- outbox.next
        pending    <-
          auth.requestPasswordReset(PasswordResetRequest(address), None)
        unknown <- auth.requestPasswordReset(
          PasswordResetRequest("nobody@example.com"),
          None,
        )
        sent <- outbox.rest
      yield
        assertEquals(pending, Right(()))
        assertEquals(unknown, Right(()))
        assertEquals(sent, Nil)

  test("an account is sent no second mail of a kind within the interval"):
    mailing(): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- auth.changeTo(alice, Some(address))
        resent     <- auth.resendConfirmation(alice, None)
        mail       <- outbox.next
        _          <- auth.confirmFrom(mail)
        _    <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        _    <- outbox.next
        _    <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        rest <- outbox.rest
      yield
        assertEquals(
          resent,
          Left(AuthRefusal.MailedRecently),
        )
        assertEquals(rest, Nil)

  test("a new address, or a link sent again, replaces the one awaiting"):
    mailing(eager): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- auth.changeTo(alice, Some(address))
        first      <- outbox.next
        _          <- auth.changeTo(alice, Some(other))
        second     <- outbox.next
        resent     <- auth.resendConfirmation(alice, None)
        third      <- outbox.next
        stale <- List(first, second).traverse[IO, Either[AuthRefusal, Unit]](
          auth.confirmFrom(_),
        )
        fresh  <- auth.confirmFrom(third)
        status <- auth.email(alice)
      yield
        assertEquals(
          resent,
          Right(EmailStatus(None, Some(other))),
        )
        assertEquals(third.to, other)
        stale.foreach(assertEquals(_, Left(AuthRefusal.EmailLinkMissing)))
        assertEquals(fresh, Right(()))
        assertEquals(
          status,
          Right(EmailStatus(Some(other), None)),
        )

  test("a reset link stops working once the password or the address changes"):
    mailing(eager): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- confirmed(harness, alice, address)
        _     <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        early <- outbox.next
        _     <- auth.changePassword(
          alice,
          PasswordChange(password, "correct-horse"),
        )
        afterPassword <- auth.resetPassword(
          PasswordReset(Outbox.token(early), "battery-staple"),
        )
        _    <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        late <- outbox.next
        _    <- confirmed(harness, alice, other, "correct-horse")
        afterAddress <- auth.resetPassword(
          PasswordReset(Outbox.token(late), "battery-staple"),
        )
      yield
        assertEquals(
          afterPassword,
          Left(AuthRefusal.EmailLinkMissing),
        )
        assertEquals(
          afterAddress,
          Left(AuthRefusal.EmailLinkMissing),
        )

  test("keeping the address withdraws a change, and giving none removes it"):
    mailing(eager): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- confirmed(harness, alice, address)
        _          <- auth.changeTo(alice, Some(other))
        pending    <- outbox.next
        kept       <- auth.changeTo(alice, Some(address))
        stale      <- auth.confirmFrom(pending)
        removed    <- auth.changeTo(alice, None)
        _    <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        sent <- outbox.rest
      yield
        assertEquals(
          kept,
          Right(EmailStatus(Some(address), None)),
        )
        assertEquals(
          stale,
          Left(AuthRefusal.EmailLinkMissing),
        )
        assertEquals(removed, Right(EmailStatus(None, None)))
        assertEquals(
          sent.map(_.subject),
          List(Wording.english.addressChangedMailSubject("Test")),
        )

  test("an expired link is refused, and awaits nothing, until swept away"):
    mailing(cheap.copy(confirmHours = 0)): harness =>
      import harness.{auth, outbox, users}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- auth.changeTo(alice, Some(address))
        mail       <- outbox.next
        expired    <- auth.confirmFrom(mail)
        status     <- auth.email(alice)
        before     <- links(harness)
        _          <- users.purgeExpired
        after      <- links(harness)
      yield
        assertEquals(
          expired,
          Left(AuthRefusal.EmailLinkMissing),
        )
        assertEquals(status, Right(EmailStatus(None, None)))
        assertEquals((before, after), (1, 0))

  test("a password change or reset withdraws an address awaiting confirmation"):
    mailing(eager): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- confirmed(harness, alice, address)
        // Someone who knows the password asks for an address of their own,
        // and is then signed out by a new password.
        _     <- auth.changeTo(alice, Some(other))
        early <- outbox.next
        _     <- auth.changePassword(
          alice,
          PasswordChange(password, "correct-horse"),
        )
        afterChange <- auth.confirmFrom(early)
        _           <- auth.changeTo(alice, Some(other), "correct-horse")
        late        <- outbox.next
        _     <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        reset <- outbox.next
        _     <- auth.resetPassword(
          PasswordReset(Outbox.token(reset), "battery-staple"),
        )
        afterReset <- auth.confirmFrom(late)
        status     <- auth.email(alice)
      yield
        assertEquals(
          afterChange,
          Left(AuthRefusal.EmailLinkMissing),
        )
        assertEquals(
          afterReset,
          Left(AuthRefusal.EmailLinkMissing),
        )
        assertEquals(
          status,
          Right(EmailStatus(Some(address), None)),
        )

  test("no number of accounts can send one address more than its share"):
    mailing(eager.copy(addressMailsPerHour = 2)): harness =>
      import harness.{auth, outbox}
      for
        people <- List("alice", "bob", "carol").traverse(signUp(auth, _))
        asked  <- people.traverse[IO, Either[AuthRefusal, EmailStatus]](
          (person, _) => auth.changeTo(person, Some(address)),
        )
        sent <- outbox.rest
      yield
        assertEquals(
          asked.map(_.left.toOption),
          List(
            None,
            None,
            Some(AuthRefusal.MailedRecently),
          ),
        )
        assertEquals(sent.size, 2)

  test("asking again for a reset never stops a link already sent from working"):
    mailing(eager): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- confirmed(harness, alice, address)
        _      <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        first  <- outbox.next
        _      <- auth.requestPasswordReset(PasswordResetRequest(address), None)
        second <- outbox.next
        reset  <- auth.resetPassword(
          PasswordReset(Outbox.token(first), "correct-horse"),
        )
        again <- auth.resetPassword(
          PasswordReset(Outbox.token(second), "battery-staple"),
        )
      yield
        assertEquals(reset.map(_._1), Right(alice))
        assertEquals(
          again,
          Left(AuthRefusal.EmailLinkMissing),
        )

  test("a reset link is only issued for the address that is its user's now"):
    mailing(): harness =>
      import harness.{auth, users}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- confirmed(harness, alice, address)
        at         <- IO.realTime.map(_.toMillis)
        issued     <- List(other, address).traverse(to =>
          users.issueLink(
            EmailLinkRow(
              to,
              alice.id,
              EmailPurpose.Reset.code,
              to,
              at,
              at + 1000,
            ),
            at - 1000,
            5,
          ),
        )
      yield assertEquals(issued, List(false, true))

  test("an address replaced or removed is told so, naming the account"):
    mailing(eager): harness =>
      import harness.{auth, outbox}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- confirmed(harness, alice, address)
        first      <- outbox.rest
        _          <- confirmed(harness, alice, other)
        replaced   <- outbox.next
        _          <- auth.changeTo(alice, None)
        removed    <- outbox.next
      yield
        assertEquals(first, Nil)
        assertEquals(
          (replaced.to, removed.to),
          (address, other),
        )
        assertEquals(
          replaced.subject,
          Wording.english.addressChangedMailSubject("Test"),
        )
        assert(
          replaced.body.contains("\"alice\""),
          replaced.body,
        )

  test("deleting an account deletes the links sent to it"):
    mailing(): harness =>
      import harness.{auth, db, outbox, users}
      for
        (alice, _) <- signUp(auth, "alice")
        _          <- auth.changeTo(alice, Some(address))
        _          <- outbox.next
        before     <- links(harness)
        _          <- db.run(users.delete(alice.id))
        after      <- links(harness)
      yield assertEquals((before, after), (1, 0))

  test(
    "a mail that cannot be sent is refused, awaits nothing, and still counts",
  ):
    Ref
      .of[IO, List[Throwable]](Nil)
      .flatMap: reported =>
        TestDb
          .users("email")
          .use: users =>
            val auth = AuthService(
              users,
              cheap,
              mailing = Some(Mailing(
                _ => IO.raiseError(Exception("The mail server is down.")),
                "Test",
                identity,
                identity,
              )),
              report = error => reported.update(error :: _),
            )
            for
              (alice, _) <- signUp(auth, "alice")
              changed    <- auth.changeTo(alice, Some(address))
              again      <- auth.changeTo(alice, Some(address))
              status     <- auth.email(alice)
              errors     <- reported.get
            yield
              assertEquals(changed, Left(AuthRefusal.Failed))
              assertEquals(
                again,
                Left(AuthRefusal.MailedRecently),
              )
              assertEquals(status, Right(EmailStatus(None, None)))
              assertEquals(errors.size, 1)

  test("a service that sends no email refuses every request about it"):
    TestDb
      .users("email")
      .use: users =>
        val auth = AuthService(users, cheap)
        for
          (alice, _) <- signUp(auth, "alice")
          changed    <- auth.changeTo(alice, Some(address))
          forgot     <-
            auth.requestPasswordReset(PasswordResetRequest(address), None)
        yield
          assert(
            !auth.rules.email,
            "the rules offer email",
          )
          assertEquals(changed, Left(AuthRefusal.NoEmail))
          assertEquals(forgot, Left(AuthRefusal.NoEmail))

  test("a logged mailer writes out the whole mail, links and all"):
    for
      written <- Ref[IO].of(List.empty[String])
      _       <- Mailer
        .logged(text => written.update(_ :+ text))
        .send(Mail(
          address,
          "Reset",
          "https://example.com/reset/abc",
        ))
      texts <- written.get
    yield
      assertEquals(texts.size, 1)
      List(
        address,
        "Reset",
        "https://example.com/reset/abc",
      ).foreach: part =>
        assert(
          texts.head.contains(part),
          s"no $part in ${ texts.head }",
        )

object EmailSuite:

  private val password = "hunter2222"

  private val address = "alice@example.com"

  private val other = "alice@example.org"

  private val eager = cheap.copy(mailIntervalSeconds = 0)

  private final case class Harness
    (
      auth: AuthService,
      outbox: Outbox,
      users: UserStore,
      db: TestDb,
    )

  extension (auth: AuthService)

    private def changeTo
      (
        user: User,
        to: Option[String],
        password: String = password,
      )
      : Answer[EmailStatus] =
      auth.changeEmail(user, EmailChange(to, password), None)

    private def confirmFrom(mail: Mail): Answer[Unit] = auth.confirmEmail(
      EmailConfirmation(Outbox.token(mail)),
      None,
    )

  private def mailing
    (policy: AuthPolicy = cheap)
    (check: Harness => IO[Unit])
    : IO[Unit] = Outbox().flatMap(outbox =>
    TestDb
      .open("email")
      .use: db =>
        val users = UserStore(TestDb.tables, db)
        check(Harness(
          AuthService(
            users,
            policy,
            mailing = Some(Outbox.mailing(outbox)),
          ),
          outbox,
          users,
          db,
        )),
  )

  /** Gives a user the given address, confirming it with the link sent to it. */
  private def confirmed
    (
      harness: Harness,
      user: User,
      to: String,
      password: String = password,
    )
    : IO[Unit] =
    for
      _    <- harness.auth.changeTo(user, Some(to), password)
      mail <- harness.outbox.next
      _    <- harness.auth.confirmFrom(mail)
    yield ()

  /** Counts the stored emailed links of every user. */
  private def links(harness: Harness): IO[Int] = harness
    .db
    .run(TestDb.tables.emailLinks.length.result)
