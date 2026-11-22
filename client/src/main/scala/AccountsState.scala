package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  AccountCreation, CreatedAccount, ManagedAccount, Manager, PasswordSetting,
  UsernameChange,
}
import com.raquo.laminar.api.L.*
import io.circe.Decoder
import io.circe.parser.decode
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * A browser-side store of the accounts the signed-in user manages, and of who
  * manages their own, driving the endpoints under `/api/accounts` and
  * `/api/auth/managers`. Every command refetches both, as does any change of
  * signed-in user, and a refused read leaves its list empty. An account's
  * invitations are answered through [[GroupsState.accept]] and
  * [[GroupsState.decline]], and its managers changed through a [[SharingState]]
  * of the segment `accounts`.
  *
  * @param auth
  *   The sign-in state of the user managing the accounts.
  *
  * @param wording
  *   The wording of this state's own messages. The server's refusals arrive
  *   already worded.
  */
final class AccountsState
  (
    auth: AuthState,
    wording: Wording = Wording.english,
  ):

  // Bound to the page, not a view, so that every request arrives whether or not
  // anything is on screen.
  private given Owner = unsafeWindowOwner

  private val errorVar: Var[Option[String]] = Var(None)

  private val pendingVar: Var[Boolean] = Var(false)

  private val refreshes = new EventBus[Unit]

  /** The accounts the signed-in user manages, by username. */
  val managed: Signal[List[ManagedAccount]] =
    listed[ManagedAccount]("/api/accounts")

  /**
    * Whoever manages the signed-in user's own account, owners first; none for
    * an account they manage alone.
    */
  val managers: Signal[List[Manager]] = listed[Manager]("/api/auth/managers")

  /** The reason the last command was refused, if any. */
  val error: Signal[Option[String]] = errorVar.signal

  /** Whether a command is in flight. Refetches do not count. */
  val pending: Signal[Boolean] = pendingVar.signal

  /** Refetches both lists, emptying those whose requests are refused. */
  def refresh(): Unit = refreshes.emit(())

  /**
    * Creates accounts, telling `created` each with its password, which is shown
    * this once.
    *
    * @param creation
    *   The accounts, the group they join and who owns them.
    *
    * @param created
    *   The handler of the accounts created, called only if they were.
    */
  def create
    (creation: AccountCreation)
    (created: List[CreatedAccount] => Unit)
    : Unit = perform(Fetch.post("/api/accounts", body = creation))(response =>
    decode[List[CreatedAccount]](response.data) match
      case Right(accounts) => created(accounts)
      case Left(_)         => errorVar.set(Some(wording.unreadableReply)),
  )

  /**
    * Renames an account.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @param username
    *   The new username.
    *
    * @param refused
    *   The handler called if the rename is refused, as when the name is taken.
    */
  def rename
    (account: Long, username: String)
    (refused: () => Unit = () => ())
    : Unit = perform(
    Fetch.put(
      s"/api/accounts/$account/username",
      body = UsernameChange(username),
    ),
    refused,
  )(_ => ())

  /**
    * Sets an account's password, signing out every session of it.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @param password
    *   The new password.
    *
    * @param set
    *   The handler called once it is set.
    */
  def setPassword
    (account: Long, password: String)
    (set: () => Unit = () => ())
    : Unit = perform(Fetch.put(
    s"/api/accounts/$account/password",
    body = PasswordSetting(password),
  ))(_ => set())

  /**
    * Suspends an account, or lifts its suspension.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @param suspended
    *   Whether it is to be suspended.
    */
  def suspend(account: Long, suspended: Boolean): Unit = perform(Fetch.put(
    s"/api/accounts/$account/suspended",
    body = suspended,
  ))(_ => ())

  /**
    * Releases an account, so that its user alone manages it again.
    *
    * @param account
    *   The identifier of the account's user.
    */
  def release(account: Long): Unit =
    perform(Fetch.post(s"/api/accounts/$account/release"))(_ => ())

  /**
    * Deletes an account and everything that belongs to it.
    *
    * @param account
    *   The identifier of the account's user.
    */
  def delete(account: Long): Unit =
    perform(Fetch.delete(s"/api/accounts/$account"))(_ => ())

  /** Discards the last error. */
  def clearError(): Unit = errorVar.set(None)

  private def listed[X : Decoder](url: String): Signal[List[X]] = auth
    .userId
    .flatMapSwitch:
      case None    => Val(List.empty[X])
      case Some(_) => EventStream
          .merge(
            EventStream.fromValue(()),
            refreshes.events,
          )
          .flatMapSwitch(_ =>
            Outcome
              .of(Fetch.get(url))
              .map:
                case Outcome.Answered(response) =>
                  decode[List[X]](response.data).getOrElse(List.empty)
                case _ => List.empty,
          )
          .startWith(List.empty)
    .observe

  private def perform
    (
      request: EventStream[Response],
      refused: () => Unit = () => (),
    )
    (answered: Response => Unit)
    : Unit =
    errorVar.set(None)
    Outcome.tracked(auth.outcome(request), pendingVar): outcome =>
      outcome match
        case Outcome.Answered(response) => answered(response)
        case Outcome.Refused(reason)    =>
          errorVar.set(Some(reason))
          refused()
        case Outcome.Unreachable(_) =>
          errorVar.set(Some(wording.unreachable))
          refused()
      refresh()
