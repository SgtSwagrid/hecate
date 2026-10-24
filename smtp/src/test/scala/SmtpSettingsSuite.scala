package com.alecdorrington.hecate
package smtp

import munit.FunSuite

class SmtpSettingsSuite extends FunSuite:

  import SmtpSettingsSuite.*

  test("A host and a sender are enough, over STARTTLS on its usual port."):
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

  test("A security without a port takes the security's usual port."):
    assertEquals(
      SmtpSettings
        .from(variables("SMTP_SECURITY" -> "TLS"))
        .map(settings => (settings.security, settings.port)),
      Some((SmtpSecurity.Tls, 465)),
    )

  test("A port and a login are read as given."):
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

  test("Without a host or a sender, nothing is configured."):
    assertEquals(
      SmtpSettings.from(without("SMTP_HOST")),
      None,
    )
    assertEquals(
      SmtpSettings.from(without("MAIL_FROM")),
      None,
    )

  test("A variable that cannot be used configures nothing, never a default."):
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
