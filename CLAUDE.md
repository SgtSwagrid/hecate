# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

This is Hecate, a Scala 3 library for user accounts, sign-in sessions, nestable user groups and permissions,
for full stack websites built on Slick, Cats Effect and Laminar, and optionally Tapir. It is in beta.

- `core` (`com.alecdorrington.hecate`, JVM + JS) - the model (`User`, `Caller`, `Group`, `Principal`, `Access`,
  `Grant`, `Resource`, `Permitted`, `Gated`, `AuthRefusal`), the wording of what the library says (`i18n/Wording`)
  and `api/Protocol`, what every server and client agree on whatever serves the requests: the cookies' names, and
  the bounds on the text a request carries.
- `server` (`com.alecdorrington.hecate.server`) - password hashing, persistence and the services (`AuthService`,
  `GroupService`, `LinkService`, `SharingService`), which know nothing of HTTP: each request is a method taking the signed-in `User`
  and answering an `Answer[X]` (`IO[Either[AuthRefusal, X]]`), never a failed `IO` (`Failures` answers an
  `AuthProblem` with its refusal, and reports anything else and answers `Failed`). A request that opens or ends a
  session answers the `SessionCookie` to set, and `Bounds` refuses text longer than `Protocol` allows (`TooLong`)
  before anything else is done with it, as a host serving the services without Tapir has no schema to do it.
  It opens no database but takes a `Transactor`; its tables are defined against a `JdbcProfile` passed to `AuthTables`
  rather than a fixed one, so identifiers go through the `prefix` parameter, never string literals; and it deletes
  nothing of the host's, taking instead a cascade hook run inside its own deletion transactions.
  It creates its own tables (`AuthTables.createIfNotExists`) and has no migrations.
  Every row lock goes through `AuthTables.locked`, never `.forUpdate` itself: Slick's SQLite profile
  lacks the capability yet still writes `FOR UPDATE`, which SQLite refuses. `Passwords` is told every
  iteration count (`AuthPolicy.hashIterations`, for new hashes, rehashing and the decoy alike), and
  `Passwords.defaultIterations` is only the policy's default, so that the host's count is never overridden.
  Usernames are shown as chosen but matched in any letter case: `users.username_key` (unique) holds
  `Username.key` of the name, written by the table's own projection from `UserRow.username`, so every
  lookup by name filters on the key, never on `username`, and an update of the name updates both.
  A group is a resource (`Resource.group`, kind `Resource.groupKind`, reserved): grants over it decide
  who manages it (`View` sees who is in it, `Edit` changes that and the group, `Own` deletes and shares
  it), its creator first holding `Own`; there is no owner column, and below the level an action needs a
  group is `GroupMissing`. `Principal.System` is a third principal, stored by its kind (never matched by
  a name): `GroupStore` takes `operators` (email addresses), and a user whose *confirmed* address is
  among them acts for it. `GroupStore.principalsOf` (the user, every enclosing group, and the system)
  is the one place a user's principals are worked out, and `usersOf` the one place they are expanded
  back; `Permissions` goes through both. The system's case passes a literal to `Principal`: an enum's
  singleton case is made before its companion's strings are set, so `Principal.systemKind` would be
  null there. Groups are joined by invitation (which records who sent it), by a request a manager
  admits (only of a group the asker can see: public, or nested in one they belong to), or by an
  invite link; `LinkStore`/`LinkService` keep one link
  per group or resource, each a random `InviteCode` (five of a-z0-9, any case, always a digit), and a link
  to a resource grants its level through `GrantStore.raise`, under the row lock the host's `resources` hook takes.
  `SharingService` lets a resource's owners, a group's included (by the host's `access` hook, else stored grants), see and change who
  holds it and manage its link, under the same lock: a resource is never left with nobody holding `Own`
  (`SharingService.orphans`, checked inside the locked transaction), no link grants `Own`, and only a principal
  the owner may address (`GroupStore.mayAddress`) is granted anything; a change to a group's grants is told as
  `Affected.Groups` (`GroupStore.grantsChanged`), as members see its owners. Deleting a group (`Own`) takes the groups
  beneath it reached through groups the deleter owns too, lifts any other nested group to the nearest survivor, and
  grants the deleter `Own` over whatever the deleted groups alone owned (`GroupStore.handOver`), so that rule holds there
  too. `GroupStore` takes the host's `resources` hook for it and extends it with groups as `GroupStore.named`, which the
  host passes to `LinkService` and `SharingService`. `GroupStore.delete` locks in the order sharing and account deletion
  do: the groups' resources (found unlocked first, and any found since once the groups are locked), the deleter's row,
  then the doomed groups, read again under their locks until none was nested beneath them meanwhile; sole ownership is
  read again under them, and a resource the hook no longer finds passes to nobody. An account takes the groups it alone
  owns, and those only such groups own (`GroupStore.soleGroupsOf`); a group someone else owns too survives.
  Email is off unless the host passes `AuthService` a `Mailing`: its `Mailer` (`Mailer.logged` for development, `smtp`'s
  `SmtpMailer`, or the host's own), the site's name, the pages the links lead to (built from the host's own base URL, never the `Host`
  header) and the wording the mail is written in. `users.email` holds an address only once the link sent to it
  is opened; links live in `email_links` as hashes, by `EmailPurpose` (`Reset`, `Confirm`), issued under the
  user's row lock (which using one takes first too): a confirmation replaces the last, a reset does not.
  `sent_mails` records every mail for a day, counted against `mailIntervalSeconds` per user and purpose and
  `addressMailsPerHour` per address, failed sends included. Every link dies with a password change, a reset
  link with a confirmed address, and a reset is only issued for the address its user holds now. The address
  replaced or removed is sent a notice (`Mailing.addressChangedMail`). `requestPasswordReset` answers at once and sends in the
  background, alike for any address, so that it cannot tell whose addresses are known; changing an address
  needs the password, and its confirmation mail names no account.
  A *guest* (`User.guest`) is an account with no password (`users.password_hash` is `NULL`), made only to follow an
  invite link, where `AuthPolicy.guests` lets guests in: `LinkService.welcome(code, name)` (given the
  `AuthService` as `guests`) makes the guest, gives them what the link leads to and opens their one session
  in one transaction (`AuthService.createGuest(name)(arrival)`, `UserStore.createGuest`), so that a dead link makes
  nobody. Their username is the name they gave, numbered where taken (`Sam 2`; `UserStore.numbered`, the
  whole transaction retried when two of one name race to a number). Nobody signs in to a guest: `verified`
  checks them against the decoy, as for an unknown username, so they cannot be told apart; they delete
  their account without a password, and `claim` (`POST /api/auth/claim`, under the row lock) gives it a
  username and password, refused `NotGuest` for anyone else. People not signed in are one `None` key of
  `LinkService`'s miss counter, with their own allowance (`strangerGuesses`): past it every stranger is told
  `GuestsPaused`, uncounted, while everyone signed in is unaffected. Never let a guest be made without a
  code that leads somewhere, or it becomes a way round that counter.
- `tapir` (`com.alecdorrington.hecate.tapir`, JVM + JS) - the Tapir endpoint descriptions (`AuthApi`, `GroupApi`,
  `LinkApi`, `SharingApi.of(segment)` per kind of resource), and `Schemas`, public so that a host's own
  endpoints carrying the model import them rather than derive their own (the hand-written codecs' shapes, as
  `Principal`'s and `Gated`'s, are described only here), whose schemas bound text as `Protocol` does;
  `Principals.path` is a principal in a path.
- `server-tapir` (`com.alecdorrington.hecate.server.tapir`) - the services served as those endpoints: `AuthEndpoints`
  (with `authenticate`, the security logic of every endpoint built on `AuthApi.secured`, and the wording every refusal
  is written in), `GroupEndpoints`, `LinkEndpoints` and `SharingEndpoints`. Only `tapir` and `server-tapir` depend on Tapir: keep it
  that way, so that a host serving its endpoints with anything else never needs it.
  Tapir also brought `scala-java-time` (and `java.util.Locale`) to every JS module, silently, so nothing in `core`
  or `client` may use `java.time` or `java.util.Locale` now: Scala.js lacks them, and only linking JS shows it.
- `smtp` (`com.alecdorrington.hecate.smtp`) - `SmtpMailer`, a `Mailer` over SMTP with Jakarta Mail (Angus), and
  `SmtpSettings` (`from(lookup)`/`fromEnv`: `SMTP_HOST`, `SMTP_PORT`, `SMTP_SECURITY`, `SMTP_USERNAME`/`SMTP_PASSWORD`,
  `MAIL_FROM`), where a variable set to anything unusable configures nothing rather than a default. Only `smtp`
  depends on Jakarta Mail: keep it that way, so that a host sending mail any other way never needs it.
- `client` (`com.alecdorrington.hecate.client`) - headless Laminar state (`AuthState`, `GroupsState`), and what a
  view makes for itself and binds (`SharingState`, `InviteLinks`, `Recipients`). Every request to a secured
  endpoint goes through `AuthState.outcome`/`explained`, which recheck the session on a refusal, as an ended
  session is refused with the same status as anything else. A request a state outliving every view makes goes
  through `Outcome.once` (or `Outcome.tracked`, which also marks it in flight), which lets go of it once it has
  answered; never `foreach` one on the window owner, which would keep every finished request subscribed.

The library tells the host whom each change it makes concerns, never what changed, through the `affected:
Affected => IO[Unit]` hook of `GroupService`, `LinkService`, `SharingService` and `AuthService` (default: tell nobody), once the
change is committed, so that a host with a socket can tell them to read again. A change to who is in a group
(or to its link) concerns its managers, its members (who see one another) and that person; a change to the group itself concerns everyone who saw
anything of it before or after (`GroupStore.surroundings`, asked before and after), and `Audience.Everyone`
when it was or is public; a followed link, a share or a changed link of a resource is `Affected.Grants`, whose audience the host knows,
with `formerly`, everyone the grants reached before, read under the resource's lock (`Permissions.holding`) and only
when the change lowers or withdraws someone's access (`SharingService.takes`), else empty;
signing out, a new password or codes and recovery are `Affected.Account`; deleting an account concerns
everyone. The one report saying what happened is `Affected.Invited`, beside an invitation's `Affected.Groups`,
for a host notifying the invitee: `GroupStore.invite` answers an `Offer` naming the invitation it made, if any. A failure to work out an audience is reported, never failing the request. `Permissions.holders` is
the reverse of `access`: everyone a grant over a resource reaches. `AuthState.refresh` reads the account again
without disturbing a user still signed in (`recheck` re-sets the user, which clears codes being shown).
`/api/auth/me` answers an empty body when nobody is signed in (how `jsonBody[Option[_]]` sends `None`), which
`AuthState.signedIn` reads as signed out; decoding it as JSON fails, and once made every recheck ignore a
session that had ended.

The library never picks a language or writes a sentence of its own. A refusal is an `AuthRefusal` value;
`Wording` (one member per phrase, so adding one fails every implementation until it is translated) turns one
into a sentence. The services answer refusals as values and never word them; the host passes
`wording: Option[String] => Wording` to `AuthEndpoints` (which `GroupEndpoints` and `LinkEndpoints` share) and to
`AuthState`, defaulting to `Wording.english`. The language arrives as a `language` cookie
(`Protocol.languageCookie`) read by `AuthApi.secured` and by `register`/`signIn`/`recover`, so
`AuthApi.Security` is `(session, language)` and `AuthEndpoints.authenticate` answers a `Caller` (user plus locale).
`AuthProblem` carries the refusal, and its `getMessage` is the English phrase, for logs and message-based tests.

See [README.md](README.md) for how a host wires it up.

### Where this code lives

This repository is a mirror. The library is developed inside a larger private project, beneath `hecate/`, and every file
here is copied from there by [GitHub Graph](https://github.com/SgtSwagrid/github-graph) whenever that project's `main`
changes, overwriting whatever is here. So make changes there, never here. The shared configuration (workflows, Scalafmt, IDE settings, `project/plugins-*.sbt`
other than `plugins-scalajs.sbt`) comes from further upstream still, in
[Scala Library Config](https://github.com/SgtSwagrid/scala-library-config), which syncs into the private project's `hecate/` first.
`build.sbt`, `release.sbt`, `project/Dependencies.scala`, `README.md` and this file belong to the library.

### Build

- `hecateCore` and `hecateTapir` are `projectMatrix`es (JVM + JS; the JS rows are `hecateCoreJS` and `hecateTapirJS`),
  `hecateServer`, `hecateServerTapir` and `hecateSmtp` are JVM (`hecateServerTapir` depends on `hecateServer`'s tests
  too, for `TestDb` and `Fixtures`), `hecateClient` is Scala.js, and the root project `hecate` only aggregates them and is never published.
- Project ids are prefixed with the library's name because the private project includes this build by reference
  (`ProjectRef(file("hecate"), ...)`), and its own projects are called `server`, `client` and `common`.
- Each matrix pins `sourceDirectory` to `(ThisBuild / baseDirectory) / "<module>" / "src"`. Don't remove it: sbt 2.0.8
  resolves a matrix's sources against the working directory, which is the host's when the build is included by reference,
  and the library then compiles to an empty JAR without a single error of its own.
- The library must never depend on anything in the project that includes it, and nothing here should assume a host, a database or a JDBC profile.
- Versions come from git tags (`sbt-ci-release`); publishing a GitHub release publishes to Maven Central.

## Instructions

### Compilation and Diagnostics

- When the user asks for help with a compilation or type error, start by running `sbt compile` to see the error for yourself.
  If there are many errors, making it unclear which one the user is referring to, ask them to clarify, and then focus only on that issue.
- IntelliJ MCP integration is active. When a request seems to implicitly refer to something the user is looking at, always check
  `mcp__ide__getDiagnostics` first to see which file(s) are open and get associated diagnostics (errors, warnings, and info hints with line numbers).

#### Testing

- After making code changes, always run `sbt compile` to verify that issues are fixed and no new ones are introduced.
- Repeatedly retry upon failure until the build succeeds. If you are unsure how to fix an issue, ask for help or refer to existing code for examples.
- Before trying to fix an error, make sure you first understand it fully.
- You should never report that a feature is complete without testing it first.

### Code Style

- You must read the [Code Style Guidelines](docs/STYLE_GUIDE.md).
- Document every public type and member: a summary, then `@param` for each explicit parameter,
  `@tparam` for each type parameter, and `@return` for any result but `Unit`, each one short
  sentence. Summaries read: types "A ...", values "The ...", Booleans "Whether ...", methods a
  third-person verb ("Sends ..."), never "Returns ...". Private members get a comment only for a
  non-obvious contract or gotcha, usually in one sentence.

### Pull Requests

When asked to publish the code changes, your task is to open one or more pull requests (PRs) to merge the changes into `main` on GitHub:

- Use `git` to check what has changed as compared to the `main` branch on `origin`.
- If the changes are thematically linked, they can be published as a single PR.
- Otherwise, you'll need to divide the changes into multiple PRs using your own judgement.
- Each PR should have a singular focus, shouldn't break anything, and should be able to be merged independently.
- Ensure that all code is staged, committed and pushed. Ensure no new files are left uncommitted, and no debug code is left in the codebase.
- When creating a PR, ensure that the title and description are clear, informative, and comprehensive.
- All feature/bugfix/etc branch names should be formatted as "feature_<short description>" or "fix_<short description>" or similar.
- All PR titles should be formatted as "[<scope>] <Short summary>", e.g. "[renderer] Fixed colour inversion bug."
- You have GitHub MCP integration that can be used to do the above.
