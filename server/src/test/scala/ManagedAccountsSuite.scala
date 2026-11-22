package com.alecdorrington.hecate
package server

import Fixtures.{cheap, enrol}
import cats.effect.{IO, Ref}
import com.alecdorrington.hecate.model.{
  Access, AccountCreation, AuthRefusal, CreatedAccount, Credentials,
  GroupDetails, Holder, Manager, NewAccount, PasswordChange,
  PasswordReplacement, PasswordSetting, Principal, Recovery, Resource, User,
  UsernameChange,
}
import munit.CatsEffectSuite
import slick.dbio.DBIO

/**
  * Tests of accounts managed by others: created in bulk, then renamed, given
  * passwords, suspended, released and deleted by whoever holds a grant over
  * them, while their own users keep their passwords and never manage their own
  * accounts through a grant.
  */
class ManagedAccountsSuite extends CatsEffectSuite:

  import ManagedAccountsSuite.*

  test("created accounts join the group at once, owned by their creator"):
    served("create"): world =>
      import world.*
      for
        coach <- signUp("coach")
        team  <- groups.create(coach.id, GroupDetails("Team"))
        made  <- service.create(
          coach,
          AccountCreation(
            List(
              NewAccount("Ada"),
              NewAccount("Ben", Some("chosen-secret")),
            ),
            team.id,
          ),
        )
        created = made.toOption.get
        listed <- service.list(coach)
        inside <- groups.managed(coach.id)
        signed <- created.traverseSignIn(auth)
        owned  <- created.traverseReplace(auth)
        told   <- reported
      yield
        assertEquals(
          created.map(_.user.username),
          List("Ada", "Ben"),
        )
        assertEquals(created(1).password, "chosen-secret")
        // A generated password has room to spare over the policy's minimum.
        assert(created(0).password.length >= cheap.minPasswordLength)
        assertEquals(
          listed.map(_.map(account => (account.user, account.access))),
          Right(created.map(_.user -> Access.Own)),
        )
        assertEquals(
          listed.map(_.flatMap(_.managers)),
          Right(List.fill(2)(Manager(
            Holder(
              Principal.Person(coach.id),
              Some("coach"),
            ),
            Access.Own,
          ))),
        )
        assertEquals(
          inside.flatMap(_.members).map(_.username).toSet,
          Set("Ada", "Ben"),
        )
        // Each must first choose a password of its own.
        assertEquals(signed, List(false, false))
        assertEquals(owned, List(true, true))
        assertEquals(
          listed.map(_.map(_.temporaryPassword)),
          Right(List(true, true)),
        )
        assert(told.contains(
          Affected.Accounts(Set(coach.id) ++ created.map(_.user.id)),
        ))

  test("creation refuses taken and repeated names, and groups not changeable"):
    served("refusals"): world =>
      import world.*
      for
        coach    <- signUp("coach")
        stranger <- signUp("stranger")
        theirs   <- groups.create(stranger.id, GroupDetails("Theirs"))
        taken    <- createIn(
          coach,
          NewAccount("STRANGER"),
          NewAccount("Cy"),
        )
        repeated <- createIn(
          coach,
          NewAccount("Dee"),
          NewAccount("dee "),
        )
        tooMany <- createIn(
          coach,
          List.tabulate(201)(n => NewAccount(s"n$n"))*,
        )
        grouped <- service.create(
          coach,
          AccountCreation(List(NewAccount("Eve")), theirs.id),
        )
        mine  <- groups.create(coach.id, GroupDetails("Mine"))
        owned <- service.create(
          coach,
          AccountCreation(
            List(NewAccount("Fay")),
            mine.id,
            owner = Some(Principal.Person(stranger.id)),
          ),
        )
        short <- createIn(coach, NewAccount("Gus", Some("short")))
        none  <- service.list(coach)
      yield
        assertEquals(
          taken,
          Left(AuthRefusal.UsernamesTaken(List("STRANGER"))),
        )
        assertEquals(
          repeated,
          Left(AuthRefusal.UsernamesTaken(List("Dee"))),
        )
        assertEquals(
          tooMany,
          Left(AuthRefusal.TooManyAccounts(200)),
        )
        assertEquals(
          grouped,
          Left(AuthRefusal.GroupMissing),
        )
        assertEquals(
          owned,
          Left(AuthRefusal.PrincipalMissing),
        )
        assertEquals(
          short,
          Left(AuthRefusal.PasswordTooShort(8)),
        )
        // Nothing was created by any of them.
        assertEquals(none, Right(List.empty))

  test("a guest creates no accounts, and nobody more than an hour allows"):
    served(
      "limits",
      AuthPolicy(hashIterations = 1000, accountsPerHour = 3),
    ): world =>
      import world.*
      for
        coach <- signUp("coach")
        guest = User(coach.id + 100, "Visitor", guest = true)
        refused <- service.create(guest, AccountCreation(Nil, 1))
        first   <- createIn(
          coach,
          NewAccount("Ada"),
          NewAccount("Ben"),
        )
        second <- createIn(
          coach,
          NewAccount("Cy"),
          NewAccount("Dee"),
        )
        third <- createIn(coach, NewAccount("Eve"))
      yield
        assertEquals(
          refused,
          Left(AuthRefusal.GuestAccount),
        )
        assert(first.isRight)
        assertEquals(
          second,
          Left(AuthRefusal.AccountsCreatedRecently(3)),
        )
        // A refused request counts for nothing.
        assert(third.isRight)

  test("an editor renames and sets passwords; a viewer or stranger cannot"):
    served("edit"): world =>
      import world.*
      for
        coach    <- signUp("coach")
        helper   <- signUp("helper")
        stranger <- signUp("stranger")
        made     <- createIn(coach, NewAccount("Ada"))
        ada = made.toOption.get.head.user
        _ <- grant(
          Resource.user(ada.id),
          helper,
          Access.View,
        )
        session <- replace(
          auth,
          "Ada",
          made.toOption.get.head.password,
          "ada-own-secret",
        )
        viewed <- service.rename(helper, ada.id, UsernameChange("Nope"))
        hidden <- service.rename(
          stranger,
          ada.id,
          UsernameChange("Nope"),
        )
        clash   <- service.rename(coach, ada.id, UsernameChange("HELPER"))
        renamed <- service.rename(coach, ada.id, UsernameChange("Ada L"))
        _       <- reported
        set     <- service.setPassword(
          coach,
          ada.id,
          PasswordSetting("new-secret"),
        )
        told   <- reported
        ended  <- auth.current(Some(session.get))
        theirs <- auth.signIn(Credentials("ada l", "new-secret"))
        reused <- auth.replacePassword(
          PasswordReplacement("ada l", "new-secret", "new-secret"),
        )
        again <- replace(
          auth,
          "ada l",
          "new-secret",
          "ada-newer-secret",
        )
        later <- signIn(auth, "Ada L", "ada-newer-secret")
      yield
        assertEquals(
          viewed,
          Left(AuthRefusal.ResourceMissing("user")),
        )
        assertEquals(
          hidden,
          Left(AuthRefusal.ResourceMissing("user")),
        )
        assertEquals(clash, Left(AuthRefusal.UsernameTaken))
        assertEquals(renamed, Right(User(ada.id, "Ada L")))
        assertEquals(set, Right(()))
        // The person is told who set it, and every session of theirs ends.
        assert(told.contains(Affected.PasswordSet(ada.id, coach.id)))
        assertEquals(ended, Right(None))
        // A manager's password signs nobody in: its user replaces it first.
        assertEquals(
          theirs.map(_._1),
          Left(AuthRefusal.PasswordChangeRequired),
        )
        assertEquals(
          reused.map(_._1),
          Left(AuthRefusal.PasswordReused),
        )
        assert(again.isDefined)
        assert(later.isDefined)

  test("a suspended account cannot be signed in to, by password or code"):
    served("suspend"): world =>
      import world.*
      for
        coach <- signUp("coach")
        made  <- createIn(
          coach,
          NewAccount("Ada", Some("ada-secret")),
        )
        ada = made.toOption.get.head.user
        session <- replace(
          auth,
          "Ada",
          "ada-secret",
          "ada-own-secret",
        )
        codes  <- auth.generateRecoveryCodes(ada, "ada-own-secret")
        _      <- service.suspend(coach, ada.id, true)
        ended  <- auth.current(Some(session.get))
        right  <- auth.signIn(Credentials("Ada", "ada-own-secret"))
        wrong  <- auth.signIn(Credentials("Ada", "not-it-at-all"))
        regain <- auth.recover(Recovery(
          "Ada",
          codes.toOption.get.codes.head,
          "another-secret",
        ))
        listed <- service.list(coach)
        _      <- service.suspend(coach, ada.id, false)
        back   <- signIn(auth, "Ada", "ada-own-secret")
        left   <- auth.recoveryCodesLeft(ada)
      yield
        assertEquals(ended, Right(None))
        assertEquals(
          right.map(_._1),
          Left(AuthRefusal.Suspended),
        )
        // Suspension is told only to someone who knows the password.
        assertEquals(
          wrong.map(_._1),
          Left(AuthRefusal.CredentialsIncorrect),
        )
        assertEquals(
          regain.map(_._1),
          Left(AuthRefusal.Suspended),
        )
        assertEquals(
          listed.map(_.map(_.suspended)),
          Right(List(true)),
        )
        assert(back.isDefined)
        // The refused recovery used up no code.
        assertEquals(left, Right(10))

  test("a manager answers invitations for the account, and invites it in"):
    served("invitations"): world =>
      import world.*
      for
        coach    <- signUp("coach")
        other    <- signUp("other")
        stranger <- signUp("stranger")
        made     <- createIn(coach, NewAccount("Ada"))
        ada = made.toOption.get.head.user
        club   <- groups.create(other.id, GroupDetails("Club"))
        mine   <- groups.create(coach.id, GroupDetails("Mine"))
        _      <- groups.invite(other.id, club.id, "Ada")
        listed <- service.list(coach)
        waiting = listed.toOption.get.head.invitations
        refused <- groupService.accept(stranger, waiting.head.id)
        _       <- reported
        taken   <- groupService.accept(coach, waiting.head.id)
        told    <- reported
        // Inviting an account one may change into a group one may change joins.
        _       <- groups.invite(coach.id, mine.id, "Ada")
        joined  <- groups.memberships(ada.id)
        pending <- groups.invitations(ada.id)
      yield
        assertEquals(
          waiting.map(_.group.name),
          List("Club"),
        )
        assertEquals(
          refused,
          Left(AuthRefusal.InvitationMissing),
        )
        assertEquals(taken, Right(()))
        // Whoever manages the account sees what awaits it, so is told too.
        assert(told.exists(tells(coach, ada)))
        assertEquals(
          joined.map(_.group.name).toSet,
          Set("Team of coach", "Club", "Mine"),
        )
        assertEquals(pending, List.empty)

  test("the person keeps their password, but deletion is the managers'"):
    served("own"): world =>
      import world.*
      for
        coach  <- signUp("coach")
        helper <- signUp("helper")
        made   <- createIn(
          coach,
          NewAccount("Ada", Some("ada-secret")),
        )
        ada = made.toOption.get.head.user
        _ <- grant(
          Resource.user(ada.id),
          helper,
          Access.Edit,
        )
        changed <- auth.changePassword(
          ada,
          PasswordChange("ada-secret", "ada-secret-2"),
        )
        selfDelete <- auth.deleteAccount(ada, "ada-secret-2")
        managers   <- service.managers(ada)
        editDelete <- service.delete(helper, ada.id)
        released   <- service.release(coach, ada.id)
        after      <- service.managers(ada)
        free       <- auth.deleteAccount(ada, "ada-secret-2")
      yield
        assert(changed.isRight)
        assertEquals(
          selfDelete.map(_ => ()),
          Left(AuthRefusal.AccountManaged),
        )
        assertEquals(
          managers.map(
            _.map(manager => (manager.holder.principal, manager.access)),
          ),
          Right(List(
            Principal.Person(coach.id)  -> Access.Own,
            Principal.Person(helper.id) -> Access.Edit,
          )),
        )
        assertEquals(
          editDelete,
          Left(AuthRefusal.ResourceMissing("user")),
        )
        assertEquals(released, Right(()))
        assertEquals(after, Right(List.empty))
        assert(free.isRight)

  test("an owner deletes an account, which takes its grants with it"):
    served("delete"): world =>
      import world.*
      for
        coach <- signUp("coach")
        made  <- createIn(coach, NewAccount("Ada"))
        ada = made.toOption.get.head.user
        deleted <- service.delete(coach, ada.id)
        gone    <- users.find(ada.id)
        listed  <- service.list(coach)
        held    <- grants.over(Resource.user(ada.id))
      yield
        assertEquals(deleted, Right(()))
        assertEquals(gone, None)
        assertEquals(listed, Right(List.empty))
        assertEquals(held, List.empty)

  test("nobody manages their own account, even through a group"):
    served("self"): world =>
      import world.*
      for
        coach  <- signUp("coach")
        member <- signUp("member")
        staff  <- groups.create(coach.id, GroupDetails("Staff"))
        _      <- enrol(groups, coach, staff.id, member)
        _      <- groups.join(coach.id, staff.id)
        _      <- grant(
          Resource.user(member.id),
          Principal.Group(staff.id),
          Access.Own,
        )
        own      <- permissions.access(member.id, Resource.user(member.id))
        theirs   <- permissions.access(coach.id, Resource.user(member.id))
        listed   <- service.list(member)
        selfEdit <- service.suspend(member, member.id, true)
        selfMade <- sharing.grant(
          coach,
          Resource.user(coach.id),
          Principal.Person(coach.id),
          Access.Own,
        )
        linked <- sharing.setLink(
          member,
          Resource.user(coach.id),
          Access.View,
        )
      yield
        assertEquals(own, None)
        assertEquals(theirs, Some(Access.Own))
        assertEquals(listed, Right(List.empty))
        assertEquals(
          selfEdit,
          Left(AuthRefusal.ManagingSelf),
        )
        assertEquals(
          selfMade,
          Left(AuthRefusal.ManagingSelf),
        )
        assertEquals(
          linked.map(_ => ()),
          Left(AuthRefusal.ResourceMissing("user")),
        )

  test("an owner hands an account to someone else through sharing"):
    served("transfer"): world =>
      import world.*
      for
        coach     <- signUp("coach")
        colleague <- signUp("colleague")
        staff     <- groups.create(coach.id, GroupDetails("Staff"))
        _         <- enrol(groups, coach, staff.id, colleague)
        made      <- createIn(coach, NewAccount("Ada"))
        ada     = made.toOption.get.head.user
        account = Resource.user(ada.id)
        _ <- sharing.grant(
          coach,
          account,
          Principal.Person(colleague.id),
          Access.Own,
        )
        _ <- reported
        _ <- sharing.revoke(
          coach,
          account,
          Principal.Person(coach.id),
        )
        told <- reported
        hers <- service.list(colleague)
        his  <- service.list(coach)
      yield
        assertEquals(
          hers.map(_.map(_.user)),
          Right(List(ada)),
        )
        assertEquals(his, Right(List.empty))
        assert(
          told.contains(Affected.Accounts(Set(colleague.id, coach.id, ada.id))),
        )

object ManagedAccountsSuite:

  /** Whether a change to groups is told to both people. */
  private def tells(one: User, other: User)(change: Affected): Boolean =
    change match
      case Affected.Groups(Audience.People(people), _) => people(one.id) &&
        people(other.id)
      case _ => false

  extension (created: List[CreatedAccount])

    /** Whether each account signs in with its password. */
    private def traverseSignIn(auth: AuthService): IO[List[Boolean]] =
      import cats.syntax.all.*
      created.traverse(account =>
        signIn(
          auth,
          account.user.username,
          account.password,
        ).map(_.isDefined),
      )

    /** Whether each account signs in replacing its password with its own. */
    private def traverseReplace(auth: AuthService): IO[List[Boolean]] =
      import cats.syntax.all.*
      created.traverse(account =>
        replace(
          auth,
          account.user.username,
          account.password,
          s"${ account.password }-own",
        ).map(_.isDefined),
      )

  /**
    * The session token signing in opens, replacing a password someone else set
    * with one's own, or `None` if refused.
    */
  private def replace
    (
      auth: AuthService,
      username: String,
      handedOut: String,
      own: String,
    )
    : IO[Option[String]] = auth
    .replacePassword(PasswordReplacement(username, handedOut, own))
    .map(_.toOption.map(_._2.token))

  /** The session token signing in opens, or `None` if refused. */
  private def signIn
    (
      auth: AuthService,
      username: String,
      password: String,
    )
    : IO[Option[String]] = auth
    .signIn(Credentials(username, password))
    .map(_.toOption.map(_._2.token))

  /** The services under test over one fresh database, with what they told. */
  private final class World
    (
      val db: TestDb,
      told: Ref[IO, List[Affected]],
      policy: AuthPolicy,
    ):

    private def tell(change: Affected): IO[Unit] = told.update(change :: _)

    val users: UserStore   = UserStore(TestDb.tables, db)
    val grants: GrantStore = GrantStore(TestDb.tables, db)
    val groups: GroupStore = GroupStore(TestDb.tables, db)

    val permissions: Permissions = Permissions(groups, grants)

    val accounts: AccountStore = AccountStore(
      TestDb.tables,
      db,
      users,
      groups,
      grants,
      _ => DBIO.unit,
    )

    val auth: AuthService = AuthService(
      users,
      policy,
      accounts = Some(accounts),
      affected = tell,
    )

    val service: AccountService = AccountService(
      TestDb.tables,
      db,
      users,
      groups,
      grants,
      Some(accounts),
      policy,
      affected = tell,
    )

    val groupService: GroupService = GroupService(groups, affected = tell)

    val sharing: SharingService = SharingService(
      grants,
      LinkStore(TestDb.tables),
      groups,
      db,
      groups.named,
      affected = tell,
    )

    def signUp(name: String): IO[User] = Fixtures.signUp(auth, name).map(_._1)

    /** Creates accounts as the user, into a group of theirs made for them. */
    def createIn
      (user: User, accounts: NewAccount*)
      : IO[Either[AuthRefusal, List[CreatedAccount]]] = groups
      .create(
        user.id,
        GroupDetails(s"Team of ${ user.username }"),
      )
      .flatMap(group =>
        service.create(
          user,
          AccountCreation(accounts.toList, group.id),
        ),
      )

    def grant
      (
        resource: Resource,
        user: User,
        access: Access,
      )
      : IO[Unit] = grant(
      resource,
      Principal.Person(user.id),
      access,
    )

    def grant
      (
        resource: Resource,
        principal: Principal,
        access: Access,
      )
      : IO[Unit] = Fixtures.grant(grants, db)(resource, principal, access)

    /** Whatever has been told since last asked, oldest first, forgetting it. */
    def reported: IO[List[Affected]] = told.getAndSet(List.empty).map(_.reverse)

  private def served
    (name: String, policy: AuthPolicy = cheap)
    (check: World => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"managed-$name")
    .use(db =>
      Ref
        .of[IO, List[Affected]](List.empty)
        .map(World(db, _, policy))
        .flatMap(check),
    )
