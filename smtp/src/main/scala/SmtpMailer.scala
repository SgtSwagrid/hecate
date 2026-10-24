package com.alecdorrington.hecate
package smtp

import cats.effect.IO
import com.alecdorrington.hecate.server.{Mail, Mailer}
import jakarta.mail.{
  Authenticator, Message, PasswordAuthentication, Session, Transport,
}
import jakarta.mail.internet.{InternetAddress, MimeMessage}
import java.util.{Date, Properties}
import scala.jdk.CollectionConverters.*

/**
  * A sender of mail through a mail server over SMTP, with Jakarta Mail.
  *
  * @param settings
  *   How to reach the mail server.
  */
final class SmtpMailer(settings: SmtpSettings) extends Mailer:

  private val session = Session.getInstance(
    SmtpMailer.properties(settings.properties),
    settings.login.map(SmtpMailer.signingIn).orNull,
  )

  private val sender = InternetAddress(settings.from, true)

  /**
    * Sends one mail, in UTF-8, failing if the mail server cannot be reached or
    * refuses it.
    *
    * @param mail
    *   The mail to send.
    */
  override def send(mail: Mail): IO[Unit] =
    IO(message(mail)).flatMap(sent => IO.blocking(Transport.send(sent)))

  private def message(mail: Mail): MimeMessage =
    val message = MimeMessage(session)
    message.setFrom(sender)
    message.setRecipient(
      Message.RecipientType.TO,
      InternetAddress(mail.to, true),
    )
    message.setSubject(mail.subject, "UTF-8")
    message.setText(mail.body, "UTF-8")
    message.setSentDate(Date())
    message

object SmtpMailer:

  private def properties(values: Map[String, String]): Properties =
    val properties = Properties()
    properties.putAll(values.asJava)
    properties

  private def signingIn(login: SmtpLogin): Authenticator = new Authenticator:

    override def getPasswordAuthentication: PasswordAuthentication =
      PasswordAuthentication(login.username, login.password)
