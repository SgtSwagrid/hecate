package com.alecdorrington.hecate
package server

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.alecdorrington.hecate.api.Protocol
import com.alecdorrington.hecate.model.{
  Access, AccountCreation, AuthRefusal, CreatedAccount, Grant, ManagedAccount,
  Manager, PasswordSet, PasswordSetting, Principal, Resource, User,
  UsernameChange,
}
import java.security.SecureRandom
import scala.util.{Failure, Success}
import slick.dbio.DBIO

/**
  * A service for accounts their users do not manage alone: creating accounts
  * for others to use, and managing them. An account is a resource
  * ([[Resource.user]]), and grants over it decide who manages it, shared
  * through [[SharingService]] as any resource's are: [[Access.View]] to see it,
  * [[Access.Edit]] to rename it, set its password, suspend it and answer its
  * invitations (see [[GroupStore.accept]]), and [[Access.Own]] to delete it,
  * release it and choose who manages it. Below the level an action needs, an
  * account is missing.
  *
  * Its user's own powers over it come from being signed in as it, never from a
  * grant, so nobody manages their own account through this service
  * ([[AuthRefusal.ManagingSelf]]); they keep their password their own, but may
  * not delete a managed account themselves. Nothing here signs anyone in as
  * anyone else.
  *
  * Every change locks the account's user's row first, as sharing it does
  * (through [[GroupStore.named]]), so that access is checked against the grants
  * that stand.
  *
  * @param tables
  *   The tables the accounts are stored in.
  *
  * @param db
  *   The database the stores use.
  *
  * @param users
  *   The store of users.
  *
  * @param groups
  *   The store of groups.
  *
  * @param grants
  *   The store of grants.
  *
  * @param accounts
  *   The store that deletes accounts, or `None` to refuse deletion, as for
  *   [[AuthService]].
  *
  * @param policy
  *   The rules for passwords, which those created or set here follow too.
  *
  * @param report
  *   The handler of failures whose detail must not reach the client. The
  *   default prints to the console.
  *
  * @param affected
  *   The hook told whom each committed change concerns (see [[Affected]]).
  */
final class AccountService
  (
    tables: AuthTables,
    db: Transactor,
    users: UserStore,
    groups: GroupStore,
    grants: GrantStore,
    accounts: Option[AccountStore] = None,
    policy: AuthPolicy = AuthPolicy(),
    report: Throwable => IO[Unit] = error => Console[IO].printStackTrace(error),
    affected: Affected => IO[Unit] = _ => IO.unit,
  ):

  import tables.profile.api.*

  private val failures = Failures(report)

  private val permissions = Permissions(groups, grants)

  /**
    * The times, in milliseconds since the epoch, that each user lately had
    * accounts made, with how many, kept in memory.
    */
  private val creations =
    Ref.unsafe[IO, Map[Long, List[(Long, Int)]]](Map.empty)

  /**
    * The times, in milliseconds since the epoch, that each user lately asked
    * for accounts under some usernames, with how many, kept in memory.
    */
  private val checks = Ref.unsafe[IO, Map[Long, List[(Long, Int)]]](Map.empty)

  /**
    * Lists the accounts a user manages, holding at least [[Access.View]] over
    * them, never their own.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the accounts, by username, or a refusal.
    */
  def list(user: User): Answer[List[ManagedAccount]] = failures.attempt(db.run(
    for
      levels <- permissions.levelsAt(user.id, Resource.userKind)
      ids = levels.keys.toSeq
      rows <- ifAny(ids)(Seq.empty[UserRow]): wanted =>
        tables.users.filter(_.id inSet wanted).sortBy(_.usernameKey).result
      suspended   <- users.suspendedAmong(ids)
      temporary   <- users.temporaryAmong(ids)
      managers    <- groups.managersAt(Resource.userKind, ids, Access.View)
      invitations <- groups.invitationsOf(ids)
    yield rows
      .map(row =>
        ManagedAccount(
          row.toUser,
          levels(row.id),
          suspended(row.id),
          temporary(row.id),
          managers.getOrElse(row.id, List.empty),
          invitations
            .getOrElse(row.id, List.empty)
            .map(invitation =>
              // Who sent it is shown only to those who may answer it.
              if levels(row.id).includes(Access.Edit) then invitation
              else invitation.copy(inviter = None),
            ),
        ),
      )
      .toList,
  ))

  /**
    * Lists whoever manages a user's own account, so that they know.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the managers, owners first, or a refusal; none for an
    *   account its user manages alone.
    */
  def managers(user: User): Answer[List[Manager]] = failures.attempt(db.run(
    groups
      .managersAt(
        Resource.userKind,
        Seq(user.id),
        Access.View,
      )
      .map(_.getOrElse(user.id, List.empty)),
  ))

  /**
    * Lists the passwords anyone else set for a user's own account, newest
    * first, each with who set it and when the user replaced it, so that a
    * password set for them is never hidden from them.
    *
    * @param user
    *   The signed-in user.
    *
    * @return
    *   An answer with the passwords set, or a refusal.
    */
  def passwordsSet(user: User): Answer[List[PasswordSet]] =
    failures.attempt(users.passwordsSetFor(user.id))

  /**
    * Creates accounts for others to use, each managed by the owner the request
    * names, and each joining the group it names at once. All are created or
    * none. A password not given is generated, and every password is answered
    * this once. A guest may create none, and nobody more in an hour than
    * [[AuthPolicy.accountsPerHour]]. Taken usernames are refused before any
    * password is hashed.
    *
    * @param user
    *   The signed-in user, who must be able to address the owner and change the
    *   group.
    *
    * @param creation
    *   The accounts, their group and their owner.
    *
    * @return
    *   An answer with the accounts and their passwords, in the order given, or
    *   a refusal.
    */
  def create
    (user: User, creation: AccountCreation)
    : Answer[List[CreatedAccount]] =
    val owner = creation.owner.getOrElse(Principal.Person(user.id))
    failures
      .attemptRefusable(
        checked(
          Option.when(user.guest)(AuthRefusal.GuestAccount),
          // An account managed by a group is managed by each of its members.
          creation
            .owner
            .collect { case Principal.Group(_) =>
              AuthRefusal.PrincipalMissing
            },
          creationProblem(creation),
        )(
          // The names count before they are checked, as a refusal names
          // those taken, and the accounts only once they are free, so that a
          // request refused for one name costs a corrected one nothing.
          mayCreate(user, owner, creation.group)
            .flatMap(
              _.fold(allowed(checks, policy.namesCheckedPerHour)(
                user,
                creation.accounts.size,
              ))(refused => IO.pure(Some(refused))),
            )
            .flatMap(_.fold(taken(creation))(refused => IO.pure(Some(refused))))
            .flatMap(
              _.fold(allowed(creations, policy.accountsPerHour)(
                user,
                creation.accounts.size,
              ))(refused => IO.pure(Some(refused))),
            )
            .flatMap:
              case Some(refusal) => IO.pure(Left(refusal))
              case None          => passwordsFor(creation).flatMap(made =>
                  store(user, owner, creation.group, made).map(Right(_)),
                ),
        ),
      )
      .flatTap(
        _.toOption
          .traverse_(created =>
            tell(createdAffects(
              owner,
              creation.group,
              created.map(_.user.id),
            )),
          ),
      )

  /**
    * Renames an account the user may change.
    *
    * @param user
    *   The signed-in user.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @param change
    *   The new username.
    *
    * @return
    *   An answer with the renamed user, or a refusal.
    */
  def rename
    (
      user: User,
      account: Long,
      change: UsernameChange,
    )
    : Answer[User] =
    val username = change.username.trim
    failures
      .attemptRefusable(
        checked(
          Bounds.name(change.username),
          Option.when(username.isEmpty)(AuthRefusal.UsernameEmpty),
        )(
          db.run(
              managing(user.id, account, Access.Edit)(
                users.rename(account, username).map(_ => ()),
              ).transactionally.asTry,
            )
            .flatMap:
              case Success(()) => users
                  .find(account)
                  .map(found =>
                    found
                      .map(_.toUser)
                      .toRight(AuthRefusal.ResourceMissing(Resource.userKind)),
                  )
              case Failure(error) if UserStore.taken(error) =>
                IO.pure(Left(AuthRefusal.UsernameTaken))
              case Failure(error) => IO.raiseError(error),
        ),
      )
      .flatTap(renamed =>
        IO.whenA(renamed.isRight)(
          tell(accountAffects(account)) *> tellNow(Affected.Account(account)) *>
            tell(groups.acquaintances(account).map(List(_))),
        ),
      )

  /**
    * Sets the password of an account the user may change, closing every session
    * of it and every link sent it by email. Its user keeps their recovery
    * codes. Reported as [[Affected.PasswordSet]], so that the host can tell
    * them who did it.
    *
    * @param user
    *   The signed-in user.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @param setting
    *   The new password.
    *
    * @return
    *   An answer with nothing, or a refusal.
    */
  def setPassword
    (
      user: User,
      account: Long,
      setting: PasswordSetting,
    )
    : Answer[Unit] = failures
    .attemptRefusable(checked(policy.passwordProblem(setting.password))(
      Passwords
        .hash(
          setting.password,
          policy.hashIterations,
        )
        .product(IO.realTime)
        .flatMap((hash, now) =>
          db.run(
            managing(user.id, account, Access.Edit)(
              users.setPassword(account, hash, user.id, now.toMillis),
            ).transactionally,
          ),
        )
        .map(Right(_)),
    ))
    .flatTap(set =>
      IO.whenA(set.isRight)(
        tellNow(Affected.Account(account)) *>
          tellNow(Affected.PasswordSet(account, user.id)),
      ),
    )

  /**
    * Suspends an account the user may change, so that nobody can sign in to it,
    * closing every session of it; or lifts its suspension.
    *
    * @param user
    *   The signed-in user.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @param suspended
    *   Whether the account is to be suspended.
    *
    * @return
    *   An answer with nothing, or a refusal.
    */
  def suspend
    (
      user: User,
      account: Long,
      suspended: Boolean,
    )
    : Answer[Unit] = failures
    .attempt(db.run(
      managing(user.id, account, Access.Edit)(users.suspend(account, suspended))
        .transactionally,
    ))
    .flatTap(done =>
      IO.whenA(done.isRight)(
        tellNow(Affected.Account(account)) *> tell(accountAffects(account)),
      ),
    )

  /**
    * Releases an account the user owns: withdraws every grant over it and lifts
    * any suspension, so that its user alone manages it again.
    *
    * @param user
    *   The signed-in user.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @return
    *   An answer with nothing, or a refusal.
    */
  def release(user: User, account: Long): Answer[Unit] = failures
    .attempt(db.run(
      managing(user.id, account, Access.Own)(
        permissions
          .holding(Resource.user(account))
          .flatMap(formerly => groups.release(account).map(_ => formerly)),
      ).transactionally,
    ))
    .flatTap(
      _.toOption
        .traverse_(formerly =>
          tellNow(Affected.Accounts(formerly + account)) *>
            tellNow(Affected.Account(account)),
        ),
    )
    .map(_.void)

  /**
    * Deletes an account the user owns, and everything that belongs to it, as
    * [[AccountStore.delete]] does, refused alike while it alone owns something.
    * Refused if the service has no [[AccountStore]].
    *
    * @param user
    *   The signed-in user.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @return
    *   An answer with nothing, or a refusal.
    */
  def delete(user: User, account: Long): Answer[Unit] =
    deleteUnless(user, account)(DBIO.successful(Option.empty[Unit])).map(_.void)

  /**
    * Deletes an account the user owns, as [[delete]] does, unless the host
    * objects: its objection is read in the deletion's transaction, under the
    * account's row lock, before anything is written, so that whatever the host
    * reads cannot change before the account is gone, if the host locks that row
    * too wherever it writes it.
    *
    * @tparam E
    *   The host's objections.
    *
    * @param user
    *   The signed-in user.
    *
    * @param account
    *   The identifier of the account's user.
    *
    * @param objection
    *   An action reading the host's objection to the deletion, if any.
    *
    * @return
    *   An answer with the objection, deleting nothing, or `None` once the
    *   account is deleted, or a refusal.
    */
  def deleteUnless[E]
    (user: User, account: Long)
    (objection: DBIO[Option[E]])
    : Answer[Option[E]] = accounts match
    case None        => IO.pure(Left(AuthRefusal.NoAccountDeletion))
    case Some(store) => failures
        .attempt(db.run((for
          formerly <- permissions.holding(Resource.user(account))
          objected <- store.deleting(account)(
            permitted(user.id, account, Access.Own) >> objection,
          )
        yield objected.toLeft(formerly)).transactionally))
        .flatTap(
          _.toOption
            .flatMap(_.toOption)
            .traverse_(formerly =>
              tellNow(Affected.Account(account)) *>
                tellNow(Affected.Groups(Audience.Everyone, Set.empty)) *>
                tellNow(Affected.Accounts(formerly)),
            ),
        )
        .map(_.map(_.left.toOption))

  /**
    * Runs a change to an account once the user is found to hold at least the
    * given access over it, under its user's row lock.
    */
  private def managing[X]
    (user: Long, account: Long, least: Access)
    (change: DBIO[X])
    : DBIO[X] =
    for
      _      <- tables.lockUser(account)
      _      <- permitted(user, account, least)
      result <- change
    yield result

  /**
    * Refuses unless the user holds at least the given access over another's
    * account; below it, the account is missing.
    */
  private def permitted(user: Long, account: Long, least: Access): DBIO[Unit] =
    if user == account then DBIO.failed(AuthProblem(AuthRefusal.ManagingSelf))
    else
      permissions
        .accessAt(user, Resource.user(account))
        .flatMap(level =>
          refuseUnless(
            level.exists(_.includes(least)),
            AuthRefusal.ResourceMissing(Resource.userKind),
          ),
        )

  /** The first reason the request is unsound, before anything is hashed. */
  private def creationProblem(creation: AccountCreation): Option[AuthRefusal] =
    val asked    = creation.accounts
    val repeated = asked
      .groupBy(account => Username.key(account.username.trim))
      .values
      .filter(_.sizeIs > 1)
      .map(_.head.username.trim)
      .toList
    Option
      .when(asked.sizeIs > Protocol.maxAccountsPerRequest)(
        AuthRefusal.TooManyAccounts(Protocol.maxAccountsPerRequest),
      )
      .orElse(
        asked.view.flatMap(account => Bounds.name(account.username)).headOption,
      )
      .orElse(Option.when(asked.exists(_.username.trim.isEmpty))(
        AuthRefusal.UsernameEmpty,
      ))
      .orElse(
        asked
          .view
          .flatMap(_.password.flatMap(policy.passwordProblem))
          .headOption,
      )
      .orElse(Option.when(repeated.nonEmpty)(AuthRefusal.UsernamesTaken(
        repeated,
      )))

  /**
    * Refuses an owner the user may not address or a group they may not change,
    * before anything is hashed. Both are checked again where the accounts are
    * stored.
    */
  private def mayCreate
    (user: User, owner: Principal, group: Long)
    : IO[Option[AuthRefusal]] =
    for
      addressable <- groups.mayAddress(user.id, owner)
      changeable  <- permissions.access(user.id, Resource.group(group))
    yield Option
      .unless(addressable)(AuthRefusal.PrincipalMissing)
      .orElse(Option.unless(changeable.exists(_.includes(Access.Edit)))(
        AuthRefusal.GroupMissing,
      ))

  /** Refuses usernames already held, before anything is hashed. */
  private def taken(creation: AccountCreation): IO[Option[AuthRefusal]] = users
    .heldAmong(creation.accounts.map(_.username.trim))
    .map(held => Option.when(held.nonEmpty)(AuthRefusal.UsernamesTaken(held)))

  /**
    * Counts some names or accounts against the user's hourly allowance kept in
    * the tally, or refuses them if they would exceed the limit.
    */
  private def allowed
    (
      tally: Ref[IO, Map[Long, List[(Long, Int)]]],
      limit: Int,
    )
    (user: User, count: Int)
    : IO[Option[AuthRefusal]] = IO
    .realTime
    .flatMap(now =>
      tally.modify: all =>
        val since  = now.toMillis - AuthPolicy.hourMillis
        val recent = all.getOrElse(user.id, List.empty).filter(_._1 > since)
        if recent.map(_._2).sum + count > limit then
          (
            all.updated(user.id, recent),
            Some(AuthRefusal.AccountsCreatedRecently(policy.accountsPerHour)),
          )
        else (all.updated(user.id, (now.toMillis, count) :: recent), None),
    )

  /** Each account's username and password, generating any not given. */
  private def passwordsFor
    (creation: AccountCreation)
    : IO[List[(String, String)]] = creation
    .accounts
    .traverse(account =>
      account
        .password
        .fold(AccountService.generate(policy.minPasswordLength))(IO.pure)
        .map(account.username.trim -> _),
    )

  /**
    * Hashes the passwords, then stores the accounts, grants their owner `Own`
    * over each and enrols them in the group, all in one transaction. Locks are
    * taken in the order group deletion takes them: the owner's row, then the
    * group's.
    */
  private def store
    (
      user: User,
      owner: Principal,
      group: Long,
      made: List[(String, String)],
    )
    : IO[List[CreatedAccount]] = made
    .parTraverseN(AccountService.hashers)((_, password) =>
      Passwords.hash(password, policy.hashIterations),
    )
    .product(IO.realTime)
    .flatMap((hashes, now) =>
      db.run(
        (
          for
            stored <- users.createAll(
              made.map(_._1).zip(hashes),
              user.id,
              now.toMillis,
            )
            _ <- groups
              .mayAddressAt(user.id, owner)
              .flatMap(refuseUnless(_, AuthRefusal.PrincipalMissing))
            _ <- DBIO.seq(
              stored.map(created =>
                grants.grant(Grant(
                  Resource.user(created.id),
                  owner,
                  Access.Own,
                )),
              )*,
            )
            _ <- groups.enrolAll(user.id, group, stored.map(_.id))
          yield stored
        ).transactionally.asTry,
      ),
    )
    .flatMap:
      case Success(stored) => IO.pure(
          stored
            .zip(made)
            .map((created, account) => CreatedAccount(created, account._2))
            .toList,
        )
      case Failure(error) if UserStore.taken(error) =>
        IO.raiseError(AuthProblem(AuthRefusal.UsernameTaken))
      case Failure(error) => IO.raiseError(error)

  /** Whom creating accounts concerns: their owners, and the group's members. */
  private def createdAffects
    (
      owner: Principal,
      group: Long,
      created: Seq[Long],
    )
    : IO[List[Affected]] =
    for
      owners <- db.run(groups.usersOf(Seq(owner)))
      around <- groups.surroundings(Seq(group))
    yield List(
      Affected.Accounts(owners ++ created),
      Affected.Groups(
        Audience.People(around.managers ++ around.members),
        around.enclosing,
      ),
    )

  /** Whom a change to an account concerns: its managers, and its user. */
  private def accountAffects(account: Long): IO[List[Affected]] = db
    .run(permissions.holding(Resource.user(account)))
    .map(managers => List(Affected.Accounts(managers + account)))

  /** Tells the host whom a committed change concerns, reporting any failure. */
  private def tell(concerned: IO[List[Affected]]): IO[Unit] = concerned
    .flatMap(_.traverse_(affected))
    .handleErrorWith(report)

  private def tellNow(concerned: Affected): IO[Unit] =
    tell(IO.pure(List(concerned)))

object AccountService:

  private val random = SecureRandom()

  private val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"

  /** The number of passwords hashed at once while creating accounts. */
  private val hashers = math.max(
    1,
    Runtime.getRuntime.availableProcessors / 2,
  )

  /**
    * Generates a password of groups of four characters with no look-alikes,
    * joined by hyphens, at least three groups (about 59 bits) and at least as
    * long as the policy asks.
    */
  private def generate(least: Int): IO[String] = IO:
    val groups = math.max(3, (least + 5) / 5)
    List
      .fill(groups)(
        List.fill(4)(alphabet(random.nextInt(alphabet.length))).mkString,
      )
      .mkString("-")
