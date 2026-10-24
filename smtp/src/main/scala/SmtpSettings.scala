package com.alecdorrington.hecate
package smtp

/**
  * How to reach a mail server.
  *
  * @param host
  *   The mail server's host name or address.
  *
  * @param port
  *   The mail server's port.
  *
  * @param security
  *   How the connection is secured.
  *
  * @param from
  *   The sender of every mail, as an address or as a name with an address, such
  *   as `Example <noreply@example.com>`.
  *
  * @param login
  *   The login, or `None` where the server asks for none.
  */
final case class SmtpSettings
  (
    host: String,
    port: Int,
    security: SmtpSecurity,
    from: String,
    login: Option[SmtpLogin],
  ):

  private[smtp] def properties: Map[String, String] = Map(
    "mail.smtp.host"              -> host,
    "mail.smtp.port"              -> port.toString,
    "mail.smtp.auth"              -> login.isDefined.toString,
    "mail.smtp.connectiontimeout" -> SmtpSettings.timeoutMillis,
    "mail.smtp.timeout"           -> SmtpSettings.timeoutMillis,
    "mail.smtp.writetimeout"      -> SmtpSettings.timeoutMillis,
  ) ++ security.properties

object SmtpSettings:

  /**
    * The wait for a mail server, in milliseconds, so that one that stops
    * answering never holds a request forever.
    */
  private val timeoutMillis = "10000"

  /**
    * Loads the settings from environment variables:
    *   - `SMTP_HOST`: the mail server.
    *   - `SMTP_PORT`: its port, else the usual one for its security.
    *   - `SMTP_SECURITY`: `starttls`, `tls` or `none`, else `starttls`.
    *   - `SMTP_USERNAME` and `SMTP_PASSWORD`: the login, else none.
    *   - `MAIL_FROM`: the sender of every mail.
    *
    * @return
    *   The settings, or `None` when `SMTP_HOST` or `MAIL_FROM` is unset, or
    *   when a set variable is invalid: an unknown security, a port that is not
    *   one, or a username or password without the other.
    */
  def fromEnv: Option[SmtpSettings] =
    from(name => sys.env.get(name).filter(_.nonEmpty))

  /**
    * Loads the settings as [[fromEnv]] does, reading each variable through the
    * given lookup instead of the environment.
    *
    * @param variable
    *   The value of the variable of the given name, or `None` when it is unset.
    *
    * @return
    *   The settings, or `None` when `SMTP_HOST` or `MAIL_FROM` is unset, or
    *   when a variable which is set cannot be used.
    */
  def from(variable: String => Option[String]): Option[SmtpSettings] =
    for
      host     <- variable("SMTP_HOST")
      sender   <- variable("MAIL_FROM")
      security <- variable("SMTP_SECURITY").fold(Some(SmtpSecurity.StartTls))(
        SmtpSecurity.fromCode,
      )
      port  <- variable("SMTP_PORT").fold(Some(security.port))(portNumber)
      login <- (variable("SMTP_USERNAME"), variable("SMTP_PASSWORD")) match
        case (Some(username), Some(password)) =>
          Some(Some(SmtpLogin(username, password)))
        case (None, None) => Some(None)
        case _            => None
    yield SmtpSettings(host, port, security, sender, login)

  private def portNumber(value: String): Option[Int] = value
    .trim
    .toIntOption
    .filter(port => port > 0 && port < 65536)

/**
  * A way of securing the connection to a mail server.
  *
  * @param code
  *   The value of `SMTP_SECURITY` that selects it.
  *
  * @param port
  *   The usual port for it, used when `SMTP_PORT` is not set.
  */
enum SmtpSecurity(val code: String, val port: Int):

  /** A connection encrypted from the start. */
  case Tls extends SmtpSecurity("tls", 465)

  /** A connection that must be upgraded to an encrypted one before sending. */
  case StartTls extends SmtpSecurity("starttls", 587)

  /** An unencrypted connection, only for a relay on the same machine. */
  case Plain extends SmtpSecurity("none", 25)

  private[smtp] def properties: Map[String, String] = this match
    case Tls => Map(
        "mail.smtp.ssl.enable"              -> "true",
        "mail.smtp.ssl.checkserveridentity" -> "true",
      )
    case StartTls => Map(
        "mail.smtp.starttls.enable"         -> "true",
        "mail.smtp.starttls.required"       -> "true",
        "mail.smtp.ssl.checkserveridentity" -> "true",
      )
    case Plain => Map.empty

object SmtpSecurity:

  /**
    * Finds the security a code selects, in any letter case.
    *
    * @param code
    *   The code, as `SMTP_SECURITY` gives it.
    *
    * @return
    *   The security, or `None` for an unknown code.
    */
  def fromCode(code: String): Option[SmtpSecurity] =
    values.find(_.code.equalsIgnoreCase(code.trim))

/**
  * A login to a mail server.
  *
  * @param username
  *   The username.
  *
  * @param password
  *   The password, which [[toString]] leaves out.
  */
final case class SmtpLogin(username: String, password: String):

  /**
    * Writes the login out without its password, which no log may hold.
    *
    * @return
    *   The username alone.
    */
  override def toString: String = s"SmtpLogin($username, <password>)"
