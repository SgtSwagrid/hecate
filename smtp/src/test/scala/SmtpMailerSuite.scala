package com.alecdorrington.hecate
package smtp

import cats.effect.{IO, Resource}
import com.alecdorrington.hecate.server.Mail
import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.util.{GreenMail, ServerSetupTest}
import jakarta.mail.internet.MimeMessage
import java.net.ServerSocket
import munit.CatsEffectSuite

class SmtpMailerSuite extends CatsEffectSuite:

  import SmtpMailerSuite.*

  test("A mail reaches the mail server whole, in UTF-8."):
    server().use: server =>
      for
        _        <- SmtpMailer(settings(server, None)).send(mail)
        received <- delivered(server)
      yield
        val message = received.head
        assertEquals(message.getSubject, mail.subject)
        assertEquals(
          message.getAllRecipients.toList.map(_.toString),
          List(mail.to),
        )
        assertEquals(
          message.getFrom.toList.map(_.toString),
          List(sender),
        )
        // SMTP's end-of-message marker takes the last line break.
        assertEquals(
          message.getContent.toString.replace("\r\n", "\n").stripTrailing,
          mail.body.stripTrailing,
        )

  test("A mail server that asks for a login is signed in to."):
    server(
      GreenMailConfiguration
        .aConfig()
        .withUser(
          "noreply@example.com",
          "mailer",
          "secret",
        ),
    ).use: server =>
      for
        _ <- SmtpMailer(settings(
          server,
          Some(SmtpLogin("mailer", "secret")),
        )).send(mail)
        received <- delivered(server)
      yield assertEquals(
        received.map(_.getSubject),
        List(mail.subject),
      )

  test("A mail server that cannot be reached fails the mail."):
    for
      port <- IO(ServerSocket(0)).flatMap(socket =>
        IO(socket.getLocalPort) <* IO(socket.close()),
      )
      sent <- SmtpMailer(SmtpSettings(
        "127.0.0.1",
        port,
        SmtpSecurity.Plain,
        sender,
        None,
      )).send(mail).attempt
    yield assert(
      sent.isLeft,
      "a mail was sent to nothing",
    )

  test("A login is never written out with its password."):
    assert(!SmtpLogin("mailer", "secret").toString.contains("secret"))

object SmtpMailerSuite:

  private val sender = "Example <noreply@example.com>"

  private val mail = Mail(
    "alice@example.com",
    "Réinitialisez votre mot de passe sur « Exemple »",
    "Bonjour « alice »,\n\nhttps://example.com/reset/abc\n",
  )

  private def server
    (configuration: GreenMailConfiguration = GreenMailConfiguration.aConfig())
    : Resource[IO, GreenMail] = Resource.make(
    IO.blocking:
      val server = GreenMail(ServerSetupTest.SMTP.dynamicPort())
      server.withConfiguration(configuration)
      server.start()
      server,
  )(server => IO.blocking(server.stop()))

  private def settings
    (
      server: GreenMail,
      login: Option[SmtpLogin],
    )
    : SmtpSettings = SmtpSettings(
    server.getSmtp.getBindTo,
    server.getSmtp.getPort,
    SmtpSecurity.Plain,
    sender,
    login,
  )

  private def delivered(server: GreenMail): IO[List[MimeMessage]] = IO
    .blocking(server.waitForIncomingEmail(5000, 1))
    .flatMap(_ => IO(server.getReceivedMessages.toList))
