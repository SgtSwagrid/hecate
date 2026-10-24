package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.i18n.Wording

/**
  * A sender of email over whatever transport the host uses: SMTP, a provider's
  * API, or a log in development.
  */
trait Mailer:

  /**
    * Sends one mail, failing if it cannot be handed on for delivery.
    *
    * @param mail
    *   The mail to send.
    */
  def send(mail: Mail): IO[Unit]

object Mailer:

  /**
    * Makes a mailer for development without a mail server, which writes each
    * mail out whole instead of sending it. Never use it in production, where
    * whatever it writes to would then hold every link.
    *
    * @param write
    *   Writes out the text of one mail, as to a log.
    *
    * @return
    *   A mailer that sends nothing.
    */
  def logged(write: String => IO[Unit]): Mailer = mail =>
    write(
      List(
        s"An email to ${ mail
            .to }, which no mail server is configured to send:",
        mail.subject,
        mail.body,
      ).mkString("\n\n"),
    )

/**
  * An email in plain text.
  *
  * @param to
  *   The recipient's address, already checked to be shaped like one with no
  *   line break.
  *
  * @param subject
  *   The subject line.
  *
  * @param body
  *   The text, with lines separated by `\n`.
  */
final case class Mail(to: String, subject: String, body: String)

/**
  * A composer of the mails this library sends: address confirmations and
  * password resets.
  *
  * @param mailer
  *   The sender of the mail.
  *
  * @param site
  *   The name of the website, as each mail names it.
  *
  * @param resetPage
  *   The absolute URL of the page that resets a password, given the link's
  *   secret. It must be of the host's choosing, never built from the request,
  *   whose `Host` header anyone can forge to have the secret sent elsewhere.
  *
  * @param confirmPage
  *   The absolute URL of the page that confirms an address, given the link's
  *   secret. Like [[resetPage]], of the host's choosing.
  *
  * @param wording
  *   The wording for the language a request names, or for `None`.
  */
final case class Mailing
  (
    mailer: Mailer,
    site: String,
    resetPage: String => String,
    confirmPage: String => String,
    wording: Option[String] => Wording = _ => Wording.english,
  ):

  /**
    * Composes the mail sending a user a link to reset their password.
    *
    * @param to
    *   The address to send it to.
    *
    * @param username
    *   The name of the account it resets.
    *
    * @param token
    *   The secret the link carries.
    *
    * @param hours
    *   The number of hours the link works for.
    *
    * @param locale
    *   The language to write it in.
    *
    * @return
    *   A mail ready to send.
    */
  def resetMail
    (
      to: String,
      username: String,
      token: String,
      hours: Int,
      locale: Option[String],
    )
    : Mail =
    val words = wording(locale)
    Mail(
      to,
      words.resetMailSubject(site),
      words.resetMailBody(site, username, resetPage(token), hours),
    )

  /**
    * Composes the mail sending an address a link that confirms it.
    *
    * @param to
    *   The address to confirm and send it to.
    *
    * @param token
    *   The secret the link carries.
    *
    * @param hours
    *   The number of hours the link works for.
    *
    * @param locale
    *   The language to write it in.
    *
    * @return
    *   A mail ready to send.
    */
  def confirmMail
    (
      to: String,
      token: String,
      hours: Int,
      locale: Option[String],
    )
    : Mail =
    val words = wording(locale)
    Mail(
      to,
      words.confirmMailSubject(site),
      words.confirmMailBody(site, confirmPage(token), hours),
    )

  /**
    * Composes the mail telling an address it was replaced or removed from its
    * account.
    *
    * @param to
    *   The former address.
    *
    * @param username
    *   The name of the account whose address it was.
    *
    * @param locale
    *   The language to write it in.
    *
    * @return
    *   A mail ready to send.
    */
  def addressChangedMail
    (
      to: String,
      username: String,
      locale: Option[String],
    )
    : Mail =
    val words = wording(locale)
    Mail(
      to,
      words.addressChangedMailSubject(site),
      words.addressChangedMailBody(site, username),
    )
