package com.alecdorrington.hecate
package smtp

import munit.FunSuite

class SmtpSettingsSuite extends FunSuite:

  import SmtpSettingsSuite.*

  test("A host and a sender are enough, and the rest is read as given."):
    // Over STARTTLS on its usual port, or a security's usual port.
    assertEquals(
      SmtpSettings.from(variables()),
      Some(SmtpSettings(
        "smtp.example.com",
        587,
        SmtpSecurity.StartTls,
        "Example <noreply@example.com>",
        None,
      )),
    )
    assertEquals(
      SmtpSettings
        .from(variables("SMTP_SECURITY" -> "TLS"))
        .map(settings => (settings.security, settings.port)),
      Some((SmtpSecurity.Tls, 465)),
    )
    assertEquals(
      SmtpSettings
        .from(variables(
          "SMTP_PORT"     -> "2525",
          "SMTP_USERNAME" -> "mailer",
          "SMTP_PASSWORD" -> "secret",
        ))
        .map(settings => (settings.port, settings.login)),
      Some((2525, Some(SmtpLogin("mailer", "secret")))),
    )

  test("A missing or unusable variable configures nothing, never a default."):
    List("SMTP_HOST", "MAIL_FROM").foreach: name =>
      assertEquals(
        SmtpSettings.from(without(name)),
        None,
        name,
      )
    List(
      "SMTP_SECURITY" -> "ssl",
      "SMTP_PORT"     -> "smtp",
      "SMTP_PORT"     -> "0",
      "SMTP_PORT"     -> "65536",
      "SMTP_USERNAME" -> "mailer",
      "SMTP_PASSWORD" -> "secret",
    ).foreach: unusable =>
      assertEquals(
        SmtpSettings.from(variables(unusable)),
        None,
        unusable,
      )

object SmtpSettingsSuite:

  private val required = Map(
    "SMTP_HOST" -> "smtp.example.com",
    "MAIL_FROM" -> "Example <noreply@example.com>",
  )

  private def variables(set: (String, String)*): String => Option[String] =
    (required ++ set).get

  private def without(name: String): String => Option[String] = required
    .removed(name)
    .get
