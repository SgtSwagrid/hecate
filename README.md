<div align="center">

  <h1>🗝️ Hecate</h1>
  <p>User accounts, sessions, groups and permissions for full stack <a href="https://www.scala-lang.org/">Scala</a> websites.</p>

  <span>
    <a href="https://github.com/SgtSwagrid/hecate/actions/workflows/build-integrity.yml"><img src="https://github.com/SgtSwagrid/hecate/actions/workflows/build-integrity.yml/badge.svg" alt="Build status" /></a>
    <a href="https://search.maven.org/artifact/com.alecdorrington/hecate-core_3"><img src="https://img.shields.io/maven-central/v/com.alecdorrington/hecate-core_3.svg" alt="Maven Central" /></a>
    <a href="https://alecdorrington.com/hecate"><img src="https://img.shields.io/badge/docs-latest-blue.svg" alt="Documentation" /></a>
  </span>

</div>

> [!WARNING]
> Hecate is in beta. It is young, it has one user, and anything may change between minor versions.

A library for user accounts, sign-in sessions, nestable user groups, and the permissions that people
and groups hold over whatever your application calls a resource.
It knows nothing of the application it serves: it opens no database, fixes no JDBC profile, needs no
particular HTTP framework, connects to no mail server unless you add `hecate-smtp`, and renders
nothing.

Named for [Hecate](https://en.wikipedia.org/wiki/Hecate), keeper of keys and guardian of gates and crossroads.

## ⬇️ Installation

Add whichever halves you need to your `build.sbt`:

```scala
libraryDependencies += "com.alecdorrington" %% "hecate-server"       % "0.1.0" // On the JVM.
libraryDependencies += "com.alecdorrington" %% "hecate-server-tapir" % "0.1.0" // On the JVM, with Tapir.
libraryDependencies += "com.alecdorrington" %% "hecate-client"       % "0.1.0" // In the browser.
libraryDependencies += "com.alecdorrington" %% "hecate-smtp"         % "0.1.0" // On the JVM, to send mail over SMTP.
```

Compiled with Scala `3.9.0`, with no intention to explicitly support older versions.

## 🏯 Layout

| Module | Platform | Contents |
|--------|----------|----------|
| [`hecate-core`](core)                 | JVM + JS | The model, and what server and client agree on.            |
| [`hecate-server`](server)             | JVM      | Password hashing, persistence, and the services.           |
| [`hecate-client`](client)             | JS       | Headless browser-side state.                               |
| [`hecate-tapir`](tapir)               | JVM + JS | The API's endpoints, described with Tapir.                 |
| [`hecate-server-tapir`](server-tapir) | JVM      | The services, served as those endpoints.                   |
| [`hecate-smtp`](smtp)                 | JVM      | A mailer that sends over SMTP.                             |

A host application uses the server half, the client half, or both;
the shared half comes with either, so the two agree on the wire format by construction.

Only the two `-tapir` modules depend on [Tapir](https://tapir.softwaremill.com/). The services in
`hecate-server` know nothing of HTTP: a host that serves its endpoints with Tapir adds
`hecate-server-tapir` and serves the endpoints it makes of them, and any other host calls the
services itself (see *Serving it without Tapir*).

## Server

Wire it up by handing the library a database to use. It opens nothing itself:

```scala
import com.alecdorrington.hecate.server.*
import com.alecdorrington.hecate.server.tapir.*
import slick.jdbc.H2Profile

// Your database handle, implementing `def run[X](action: DBIO[X]): IO[X]`.
class MyDb(...) extends Transactor

val tables = AuthTables(H2Profile) // Or any other JDBC profile.
val auth   = AuthService(UserStore(tables, db))
val groups = GroupService(GroupStore(tables, db))
val links  = LinkService(LinkStore(tables), GroupStore(tables, db), GrantStore(tables, db), db)

// On startup, alongside your own schema creation:
db.run(tables.createIfNotExists)

// Serve them with the rest of your endpoints:
val served    = AuthEndpoints(auth)
val endpoints = served.api ++ GroupEndpoints(groups, served).api ++
  LinkEndpoints(links, served).api ++ myOwnEndpoints
```

`AuthTables` is parameterised on the Slick profile, so the library is not bound to any
one database. Pass `prefix` if its tables need to sit in a namespace of their own.
That prefix becomes part of the table names, so it must be a constant of your own choosing
rather than anything a request carries.

The stores expect `READ COMMITTED` or stricter, and use `SELECT … FOR UPDATE` where the
profile has it, to serialise the changes that no table constraint can (see *Groups* below).
On SQLite, which has neither, one writer at a time makes the locks unnecessary, so the
library leaves them out. Slick's SQLite profile still writes the clause when a query asks
for it, and SQLite refuses the statement, so a host on SQLite should check its profile's
`capabilities` for `JdbcCapabilities.forUpdate` before locking its own rows (the
`resources` example under *Invite links* assumes a database that has it).

### Securing your own endpoints

Build an endpoint on `AuthApi.secured` and give it `AuthEndpoints.authenticate` as its security
logic. The `Caller` is then the first argument of the endpoint's logic: the signed-in `User`,
with the language their request asked to be answered in.

```scala
import com.alecdorrington.hecate.tapir.AuthApi

val myEndpoint = AuthApi.secured.get.in("api" / "things").out(jsonBody[List[Thing]])

myEndpoint
  .serverSecurityLogic(served.authenticate)
  .serverLogic(caller => _ => thingsOwnedBy(caller.id))
```

An endpoint of your own that carries the library's model imports its Tapir schemas from
`hecate-tapir`'s `Schemas.given`, rather than deriving its own, and names a principal in a path
with `Principals.path` (`person/7`, `group/7`, `system/0`).

### Serving it without Tapir

Every request is a method of `AuthService`, `GroupService` or `LinkService`, taking the
signed-in `User` where it needs one. Each answers an `Answer[X]`, an
`IO[Either[AuthRefusal, X]]`: what was asked for, or why it was refused, never a failed `IO`. A
failure that is not the user's to understand is handed to the service's `report` and answered
as `AuthRefusal.Failed`, so no driver or SQL detail reaches a client. To serve one yourself:

- Read the session token from the `Protocol.sessionCookie` cookie, and resolve it with
  `auth.signedIn(token)`, which refuses with `AuthRefusal.SignedOut` when nobody is signed in.
- Wherever an answer carries a `SessionCookie` (signing in or out, a new password, a recovery,
  a deleted account), set its `header` as the reply's `Set-Cookie`.
- Answer a refusal with `400 Bad Request` and the refusal worded for its reader,
  `wording.phrase(refusal)`, as plain text (see *Speaking the user's language*); answer anything
  else as JSON, with the model's circe codecs.
- Serve each at the path in the table under *API*, which `hecate-client` calls.

Text a request carries is bounded (`Protocol.maxNameLength` for names and codes,
`Protocol.maxPasswordLength` for passwords), and the services refuse longer text as
`AuthRefusal.TooLong` before doing anything with it, so a password nobody would type is never
hashed, however it arrives.

Nothing here throttles anything. Sign-in costs a PBKDF2 derivation whether the password is
right or wrong, which bounds how fast an attacker can guess, but a library that opens no
socket cannot rate-limit by address: put that in front of these endpoints yourself. Recovery
codes are the one exception, and deliberately so — each is about 49 bits of entropy, which
no amount of guessing gets through.

Sessions live in an HTTP-only, `SameSite=Strict` cookie, and expire in the store as well
as in the browser, so a leaked token cannot outlive its expiry. Nothing clears out
the rows of sessions that have expired, since an expired session is refused whether or
not its row is still there; schedule `UserStore.purgeExpired` if you would rather the
table did not keep them. Passwords are stored as
salted PBKDF2 hashes, compared in constant time; an unknown username is checked against a
decoy hash so that sign-in takes the same time whether or not the account exists.

Usernames are trimmed of surrounding space and matched whatever their letter case: `Alice`
signs in as `alice`, and once one is taken so is the other. A username is still shown as it
was chosen; the users table keeps its folded form (`Username.key`) in a unique column of its
own, `username_key`, which every lookup goes through.

Tune the session lifetime and the password rules with `AuthPolicy`:

```scala
AuthService(users, AuthPolicy(sessionSeconds = 3600, minPasswordLength = 12))
```

Passwords are derived with PBKDF2-HMAC-SHA256 at `AuthPolicy.hashIterations` iterations,
which defaults to OWASP's current figure, and the decoy is derived at the same count, so
that timing still says nothing. Each stored hash carries the count it was derived under,
so raising it later leaves every stored password verifiable, and a password stored under
fewer is derived again at your count the next time its owner signs in. One stored under
more is left as it is.

### Speaking the user's language

The library never writes a sentence of its own choosing. Every refusal is an `AuthRefusal`,
and a `Wording` turns one into a sentence; `Wording.english` is the only one the library
carries. To answer in more languages, implement `Wording` once per language and hand
`AuthEndpoints` a function from the request's language to the wording it should use, which
`GroupEndpoints` and `LinkEndpoints` share:

```scala
AuthEndpoints(auth, wording = locale => myStrings(locale))
```

The language reaches you as the value of a `language` cookie (`Protocol.languageCookie`), which
the client sets for its whole origin: a language code (`de`) or a locale tag (`de-AT`), or
`None` when the request names none. Endpoints built on `AuthApi.secured` read it alongside the
session cookie, and `register`, `signIn`, `recover`, the email endpoints and following an invite link
as a guest read it too, so a refusal is worded for its reader even before anyone is signed in. `Caller.locale` carries it into your own endpoints,
for wording your application's own refusals the same way. Without Tapir, read the cookie
yourself and word each refusal with `wording.phrase`. `AuthProblem.getMessage` stays English,
for logs.

### Groups

Groups nest to arbitrary depth through `Group.parentId`. A group is itself a resource
(`Resource.group(id)`, of the kind `Resource.groupKind`, which your own resources must not use),
so who manages it is decided by grants over it, shared like any resource's (see *Sharing*):
`View` sees who is in it, invited to it and asking to join (`GET /api/groups`, a `ManagedGroup`
each, with the reader's access); `Edit` changes that and the group itself, its name, publicity
and invite link, and nests groups inside it; and `Own` also deletes it and chooses who manages
it. Its creator first holds `Own`, and it may then be run by several people, by another group or
by the system. A group someone may not act on at the level an action needs is reported to them as
missing (`GroupMissing`). Its members see it, its owners (each a `Holder`: a user, a group by
name, or the system) and one another (`GET /api/groups/mine`, a `Membership` each, naming the
groups enclosing it, which they belong to through it, without their members), but not who
is invited to it or asking to join; anyone else sees a group only by name, and only if they are invited to it or may ask
to join it (see *Joining a group* below). Membership propagates *upwards*: a member of a
group is effectively a member of every group it is nested inside, so anything addressed
to a department also reaches the members of each team within it. `GroupStore.enclosing`
returns exactly that set, and is the intended basis for "what may this user see?".

Deleting a group deletes every group beneath it that its deleter owns too; any other group nested
inside it moves up to the nearest group that remains. To delete whatever your application
attaches to a group in the same transaction, pass a cascade:

```scala
GroupStore(
  tables,
  db,
  principals =>
    val groups = principals.collect { case Principal.Group(id) => id }
    myTable.filter(_.groupId inSet groups).delete.map(_ => ()),
)
```

The cascade is handed `Principal`s rather than bare identifiers, because the same hook
serves account deletion, where the principal is the user themselves; a user and a group
may share an identifier, so match on the kind you mean. It runs before the groups
themselves are removed, so it may still join on them.

Whatever the deleted groups alone owned passes to whoever deleted them, so that no resource is
left without an owner. Pass `GroupStore` the `resources` hook that names and locks your own
resources, so that this happens under each resource's row lock and never to a resource deleted
meanwhile. `GroupStore.named` is that hook with groups added: pass it, not your own, to
`LinkService` and `SharingService`, so that groups are shared like everything else:

```scala
val groups = GroupStore(tables, db, cascade, resources)
LinkService(links, groups, grants, db, groups.named)
```

### The system

`Principal.System` is a principal nobody signs in as. Name the people who act for it by their
email addresses, and anyone whose *confirmed* address is among them holds whatever is granted to
it:

```scala
GroupStore(tables, db, cascade, resources, operators = Set("ops@example.com"))
```

So something owned by the system belongs to every operator, and outlives any one of their
accounts. `GroupStore.principalsOf(user)` lists whom a user acts as (themselves, every group
enclosing them, and the system if they act for it), as `GET /api/principals` tells the client;
`Permissions` resolves every grant against it. An operator may address the system, and so share
their own things with it. Principals are told apart by their stored kind, never their names, so a
user or group called "System" is just that.

### Joining a group

Nobody joins a group without consenting, and nobody joins one without the agreement of someone
who may change it (a *manager*, holding `Edit`) either. Whichever side agrees first, the other
completes it:

- **A manager** joins at once (`PUT /api/groups/{group}/membership`).
- **An invitation**: a manager invites a user by name, and the user accepts. The group
  reaches them in no way until then: `enclosing` counts memberships alone. The invitation
  names whoever sent it.
- **A request**: a user asks to join a group they can see, and a manager admits or declines
  them. A user can see a group that is *public* (`Group.public`), and any group nested inside
  one they are a member of. `GET /api/groups/joinable` lists them.
- **An invite link**: a manager makes a link, and whoever follows it joins (see *Invite
  links* below).

Asking to join a group you are invited to accepts the invitation, and inviting someone who
has asked admits them, since both sides have then agreed. Declining either deletes it, and
either side may ask again; leaving a group just ends the membership.

Changes to who is in, invited to or asking to join a group lock that group's row first
(`SELECT … FOR UPDATE`), so a double-clicked invite or two tabs accepting at once cannot
duplicate a row. The lock is skipped on SQLite, which admits one writer at a time anyway.

Inviting a username that does not exist is reported as such. That discloses whether an
account exists, and is a deliberate trade so that someone inviting a list of names knows
which they typed wrongly.

### Invite links

An invite link is known by a short random code alone (`InviteCode`: five letters or digits,
at least one a digit so that a code never spells a word, read without regard to case, and never
chosen by anyone), so that a host can put it anywhere in
its own URLs, as short as `https://example.com/k3x9q`. It leads either to a group, which
following it joins, or to a resource, over which following it grants the access the link
carries, never lowering any the follower already holds. `LinkApi` shows where a link leads
(`GET /api/invite-links/{code}`), so that nobody follows one blind, and follows it
(`POST /api/invite-links/{code}/follow`).

Each group and each resource has at most one link, which its owners may replace with a new
code, ending the old one, or turn off. A group's managers manage its link through `GroupApi`.
A resource's owners manage its link through `SharingService` (see *Sharing* below). Tell
`LinkService` what your resources are called, locking the row, so that a link never grants access
to something deleted meanwhile:

```scala
LinkService(links, groups, grants, db, resources = {
  case Resource("document", id) => documents.filter(_.id === id).forUpdate.map(_.title).result.headOption
  case _                        => DBIO.successful(None)
})
```

`GrantStore.revokeOver`, which you already call when deleting a resource, turns its link off
too. Codes are short enough to type, which means they can be guessed: there are some fifty
million, so a host with many live links should limit how fast anyone may try them.

### Guests

Someone given an invite link need not have an account to follow it. Where the policy lets
guests in, a person who is not signed in gives only a name, and `LinkService.welcome` makes
them a *guest* (`User.guest`), gives them what the link leads to and signs them in, all in one
transaction, so that a link leading nowhere makes nobody. A guest is made only this way: never
without a link that leads somewhere.

```scala
val auth  = AuthService(users, AuthPolicy(guests = true))
val links = LinkService(store, groups, grants, db, resources, guests = Some(auth))
```

A guest's username is the name they gave, numbered where somebody has it already (`Sam 2`,
`Sam 3`), so that whoever invited them knows who they are. A guest has no password, so nobody
can sign in to their account: their one session is the only way in, and once it ends, nobody
can reach the account again, which stays, with whatever of theirs others may still need. A
guest keeps their account by *claiming* it (`POST /api/auth/claim`), choosing a username and
password, after which it is an account like any other. A guest deletes their account without
a password, as their session is all that vouches for them.

Nothing tells people who are not signed in apart, so the codes leading nowhere that they try
are counted together, against an allowance of their own (`strangerGuesses`, 100 an hour by default):
past it, no link may be followed as a guest until the hour has passed, and each is told so
(`GuestsPaused`), which says nothing of whether their own link leads anywhere. Everyone signed
in is unaffected. `GET /api/auth/rules` tells clients whether guests are let in.

### Accounts

Users can change their password, give an email address, recover a forgotten password with
that address or a recovery code, and delete their account.

**Changing a password** needs the current password. It signs out every other session the
user had and gives this one a fresh session, so anyone who knew the old password is
signed out along with them.

**Recovery** needs no email and no administrator. A signed-in user generates a set of ten
one-time recovery codes, after giving their password again, and writes them down; the
server keeps only their hashes and never shows them again. A user who forgets their
password gives their username, one unused code and a new password, and is signed in.
Each code works once, and generating a new set invalidates the old one. A failed
recovery never says whether the username or the code was wrong.

**Email** is off unless the host passes a `Mailing`. The library composes each mail itself,
worded by the host's `Wording`, and hands it to a `Mailer`, which sends it however the host
sends mail. `hecate-smtp`'s `SmtpMailer` sends through any mail server that speaks SMTP, and
`SmtpSettings.fromEnv` reads where that is from the environment; a host sending through a
provider's API writes a `Mailer` of its own, and `Mailer.logged` writes each mail out in
development instead.

```scala
val mailer = SmtpSettings.fromEnv match
  case Some(settings) => SmtpMailer(settings)
  case None           => Mailer.logged(text => IO.println(text))

val mailing = Mailing(
  mailer      = mailer,
  site        = "Example",
  resetPage   = token => s"https://example.com/reset/$token",
  confirmPage = token => s"https://example.com/confirm/$token",
  wording     = locale => myStrings(locale),
)
val auth = AuthService(users, mailing = Some(mailing))
```

`SmtpSettings.fromEnv` reads these variables, and configures nothing (`None`) when `SMTP_HOST` or
`MAIL_FROM` is unset, or when any of them is set to something it cannot use, rather than
quietly falling back to a default:

| Name            | Meaning                                                                          |
|-----------------|----------------------------------------------------------------------------------|
| `SMTP_HOST`     | The mail server.                                                                 |
| `SMTP_PORT`     | Optional. Its port; by default `587`, or `465` with `tls`, or `25` with `none`.  |
| `SMTP_SECURITY` | Optional. `starttls` (the default), `tls`, or `none` for a relay on the machine. |
| `SMTP_USERNAME` | Optional, with `SMTP_PASSWORD`. What to sign in to the mail server with.         |
| `MAIL_FROM`     | Whom mail is from, as `Example <noreply@example.com>`.                           |

To read them under other names, pass `SmtpSettings.from` a lookup of your own.

The two pages are the host's: the first asks for a new password and sends it, with the token,
to `POST /api/auth/password/reset`; the second, once its reader asks, sends its token to
`POST /api/auth/email/confirm`. Never confirm merely because the page opened: mail scanners open
every link, and so may someone who never asked for the address to be used. Build their addresses
from a base URL of your own configuration, never from the request's `Host` header, which anyone
can forge to have a token sent to a site of theirs.

A signed-in user gives an address, with their password, since whoever holds the address can
reset it. The address is sent a link, and becomes theirs only once that link is opened, so nobody
can claim an address that is not theirs; until then their old address, if any, stays in force.
The confirmation names no account, as the address is anyone's to type. Several accounts may
share one address.

A user who forgets their password gives the address, and every account that has confirmed it is
sent a link, naming the account, that resets its password and signs in, closing every other
session. The request is answered at once, and alike whether any account has the address or not,
with the mail sent in the background, so that it cannot be used to find out whose addresses are
known. A reset link works once and for an hour, and asking again never stops one already sent
from working; a confirmation works for a day, and only the latest. Every link stops working when
the password changes, so that nobody signed out by a new password keeps a way back in, and a
reset link when the address is replaced. An address replaced or removed is told so, naming the
account, so that its owner learns if someone else did it.

Links are stored only as hashes. An account is sent at most one mail of each kind a minute, and an
address at most five mails an hour, whichever accounts they are for, so that no number of
accounts can flood an inbox; a mail counts whether or not it could be delivered. Nothing here
knows who asks, so limit requests per address yourself if you need to. Tune the lifetimes and
the limits with `AuthPolicy.resetHours`, `confirmHours`, `mailIntervalSeconds` and
`addressMailsPerHour`, and sweep expired links and old mail records away with
`UserStore.purgeExpired`, as for sessions.

**Deleting an account** needs the password, and removes everything that belongs to the
user in one transaction: their sessions, recovery codes and links sent by email, the groups they
alone own and any that only those groups own (with those groups' members, invitations, requests,
links and grants), their memberships, invitations and requests elsewhere, the invite links they
made, the grants they hold, and whatever the host application attaches to them. A group someone
else owns too survives, as does one the system owns.
It is refused while the user, with the groups it takes, is the only owner of some other resource,
which would otherwise be left owned by nobody; they must first pass it on or delete it. The
host's cascade runs before that check, in the same transaction, so a resource it deletes with
the account (one nobody else has any use for, say), grants and all, is no reason to refuse,
and a refusal rolls the cascade back with everything else. Another
owner means another principal holding `Own`: a group or the system counts, even a group with no members
left in it, so this guarantees that something still owns the resource rather than that
somebody can still open it.
It is off unless the host application passes an `AccountStore`:

```scala
val groups   = GroupStore(tables, db, cascade)
val grants   = GrantStore(tables, db)
val accounts = AccountStore(tables, db, users, groups, grants, cascade)
val auth     = AuthService(users, accounts = Some(accounts))

// One cascade for both: the groups of a deleted subtree, or a deleted user.
def cascade(principals: Seq[Principal]): DBIO[Unit] = ...
```

Pass it only once the cascade removes everything of the host's that points at a user.
Until then `AuthService.deleteAccount` refuses, `AuthEndpoints` does not serve it, and `GET /api/auth/rules` reports
`accountDeletion: false` so that clients hide the option.

`GET /api/auth/rules` also reports the minimum password length, so that a form can state
the rule before a password is refused rather than after, and whether email is sent,
so that clients offer it only then.

### Permissions

A grant gives a `Principal` (a `Person`, a `Group` or the `System`) one level of `Access` over a
`Resource`: `View`, `Edit` or `Own`, each including those below it. Resources are named in
your application's terms, as a kind and an identifier, so the library never knows what they are.

```scala
val grants      = GrantStore(tables, db)
val permissions = Permissions(groups, grants)

// In the transaction that creates the thing, so that it is never left without an owner:
grants.grant(Grant(Resource("document", id), Principal.Person(creator), Access.Own))

permissions.access(user, Resource("document", id))       // The highest access reaching the user.
permissions.accessible(user, "document", Access.View) // Every document they may see.
```

A grant to a group reaches every member of it and of every group nested inside it, but never
the other way round, and a grant to the system reaches every operator. `GrantStore`'s writes are returned as actions rather than run, so that
they can join the transaction that creates or deletes the resource; lock the resource's own
row before granting or revoking, as nothing else can.

Two small types carry access to the client: `Permitted` pairs a value with the access its
reader holds over it, and `Gated` marks one part of a resource as shown, absent, or
withheld from this reader, which are never to be conflated.

### Sharing

`SharingService` lets the owners of a resource see who holds it, grant access to anyone they may
address (see *Whom a user may address*), withdraw it from anyone who holds it, and manage its invite
link. Two rules hold throughout: a resource is never left with nobody holding `Own`, and no link
grants `Own`, which only an owner naming someone confers. Each change locks the resource's row
through the same `resources` hook as `LinkService`, so that two owners giving up ownership at once
cannot each see the other still holding it; and deleting a group passes whatever it alone owned to
whoever deleted it. Serve it once per kind of resource, beneath that kind's own path, and serve a
group's grants alone, as its link is `GroupApi`'s:

```scala
val sharing = SharingService(grants, LinkStore(tables), groups, db, groups.named)

val sharingEndpoints = SharingEndpoints(sharing, "document", SharingApi.of("documents"), served).api
val groupManagers    = SharingEndpoints(sharing, Resource.groupKind, SharingApi.of("groups"), served).grants
```

A change to a group's grants is told as `Affected.Groups`, as its members see its owners.

A resource someone may not see is reported to them as missing (`ResourceMissing`), and one they
see but do not own as `NotOwner`. Only stored grants make an owner, but if your application
derives access of its own, pass it as `access`, so that a resource its user may see is never
reported to them as missing:

```scala
SharingService(grants, links, groups, db, resources, access = Some(myAccess))
```

### Telling people what changed

Every service that changes something takes an `affected` hook, which it calls once the change is
committed with whom it concerns, so that a host with a live connection (a websocket, say) can tell
those people to read again. It never says what changed, only whose view of it did: whoever is told
asks again, through the endpoints that check what they may see, so telling them discloses nothing.

```scala
GroupService(groups, affected = {
  case Affected.Groups(Audience.People(ids), _) => tellEach(ids)
  case Affected.Groups(Audience.Everyone, _)    => tellEveryone
  case Affected.Invited(invitation, _, invitee) => alert(invitee, invitation)
  case Affected.Grants(resource, formerly)      => tellWhoeverSees(resource, formerly)
  case Affected.Account(user)                   => tellEach(Set(user))
})
```

`Affected.Invited` is the one report that says what happened: an invitation sent, beside the
change to the group's members, for a host that tells people they were invited (with a
notification, say). It is reported only when an invitation is made, never when inviting someone
already invited or a member changes nothing, or enrols the inviter or an applicant at once.

A change to who is in a group, invited to it or asking to join it concerns the group's managers, its
members, who see one another, and that person; a change to the group itself (its name, nesting, visibility or existence)
concerns everyone who saw anything of it before or sees anything of it after, and everyone at all
when it was or is public. `Affected.Groups` also names every group whose members may have changed,
with those enclosing them, as anything the host addressed to one of them may now reach someone else.
Sharing a resource, following its invite link or changing that link changes the grants over it: the
host alone knows who may see it, `Permissions.holders` names everyone a grant over it reaches, and
`formerly` names everyone the grants reached before, who may no longer see it, whenever the change
lowered or withdrew someone's access (it is empty otherwise, as nobody lost anything). Signing out, a new password,
email address or recovery codes, and a recovery concern the account's own sessions, which may be open elsewhere;
deleting an account concerns everyone, as it leaves every group and grant it held.

### Whom a user may address

`GroupStore.addressable(user)` lists the principals a user may give something to (a document,
access): themselves, the system if they act for it, every group they may change (`Edit`), and
every member of those groups. Invitees are excluded, as the relation must be one the recipient
consented to and can end. `GroupStore.addressersOf(user)` is the reverse: whoever may change a
group the user belongs to, directly or through nesting.
`GroupStore.mayAddress(user, principal)` answers the same question for one principal in
a single query.

## Client

`AuthState` and `GroupsState` are headless: they expose signals and commands, and your
application owns every pixel.

```scala
import com.alecdorrington.hecate.client.{AuthState, GroupsState}

val auth   = AuthState() // Or AuthState(myWording) to speak another language.
val groups = GroupsState(auth)

auth.signIn("alice", "hunter2222")
div(child <-- auth.user.map {
  case Some(user) => span(s"Signed in as ${ user.username }")
  case None       => signInForm(auth)
})
```

`AuthState.user` changes on startup, sign-in and sign-out, so other state can follow it to
refetch whatever belongs to the user. Until `AuthState.ready` is `true` nothing is known
yet, and a `None` user must not be read as "signed out". `GroupsState.forest` nests the
server's flat group list for rendering. Both states take a `Wording` for the few things they
say themselves; everything the server refuses arrives already worded.

When your server says something changed (see *Telling people what changed*), call
`GroupsState.refresh()` to read the groups again, or `AuthState.refresh()` to read the account
again: it signs the user out here if their session ended elsewhere, and otherwise leaves them as
they were, recovery codes still on screen included.

`AuthState.email` is the signed-in user's address and any awaiting confirmation, and
`requestPasswordReset`, `resetPassword`, `changeEmail`, `resendConfirmation` and `confirmEmail` drive
the email endpoints, each confirming its success in `AuthState.notice`, since none of them
changes anything else on screen. `requestPasswordReset` says the same whatever the address, as the
server does.

`AuthState.welcome(code, name)(arrived)` follows an invite link as a new guest, telling
`arrived` what the link gave them just before they are signed in, so that you can go there
first; `claim(username, password)` keeps a guest's account. Offer the first only where
`AuthRules.guests` says guests are let in.

Three more are made by a view for what it shows, and start every request through its own bindings:
`SharingState(auth, segment, id, reloads)`, one resource's grants and link as its owner sees and
changes them; `InviteLinks(auth, groups)`, which shows where a link leads and follows it; and
`Recipients(auth, groups)`, whom the user may address, for a picker of people and groups.

Your own requests to endpoints built on `AuthApi.secured` go through `AuthState.outcome`, or
`explained` to read what they answer: an ended session is refused like any other request, so these
ask again who is signed in whenever one is refused.

## API

| Method   | Path                                    | Purpose                                   |
|----------|-----------------------------------------|-------------------------------------------|
| `POST`   | `/api/auth/register`                    | Create an account and sign in.            |
| `POST`   | `/api/auth/sign-in`                     | Sign in.                                  |
| `POST`   | `/api/auth/claim`                       | Keep a guest's account, with a password.  |
| `POST`   | `/api/auth/sign-out`                    | Sign out.                                 |
| `GET`    | `/api/auth/me`                          | Identify the signed-in user.              |
| `GET`    | `/api/auth/rules`                       | Describe the rules for accounts.          |
| `PUT`    | `/api/auth/password`                    | Change your password.                     |
| `GET`    | `/api/auth/recovery-codes`              | Count your unused recovery codes.         |
| `POST`   | `/api/auth/recovery-codes`              | Generate new recovery codes.              |
| `POST`   | `/api/auth/recover`                     | Regain an account with a code.            |
| `POST`   | `/api/auth/password/request-reset`      | Have links to reset a password sent.      |
| `POST`   | `/api/auth/password/reset`              | Reset a password with a link.             |
| `GET`    | `/api/auth/email`                       | See your email address.                   |
| `PUT`    | `/api/auth/email`                       | Change or remove your email address.      |
| `POST`   | `/api/auth/email/resend`                | Send a confirmation again.                |
| `POST`   | `/api/auth/email/confirm`               | Confirm an email address with a link.     |
| `POST`   | `/api/auth/account/delete`              | Delete your account.                      |
| `GET`    | `/api/groups`                           | List the groups you manage, and who is in them. |
| `GET`    | `/api/principals`                       | List whom you act as.                     |
| `GET`    | `/api/groups/mine`                      | List groups you are in, with members.     |
| `GET`    | `/api/groups/joinable`                  | List the groups you may ask to join.      |
| `POST`   | `/api/groups`                           | Create a group.                           |
| `PUT`    | `/api/groups/{group}`                   | Rename or move a group.                   |
| `DELETE` | `/api/groups/{group}`                   | Delete a group and the subgroups you own. |
| `PUT`    | `/api/groups/{group}/public`            | Make a group public, or private again.    |
| `PUT`    | `/api/groups/{group}/invite-link`       | Give a group an invite link.              |
| `POST`   | `/api/groups/{group}/invite-link`       | Replace a group's invite link.            |
| `DELETE` | `/api/groups/{group}/invite-link`       | Turn off a group's invite link.           |
| `POST`   | `/api/groups/{group}/invitations`       | Invite a user.                            |
| `PUT`    | `/api/groups/{group}/members/{member}`  | Admit someone who asked to join.          |
| `DELETE` | `/api/groups/{group}/members/{member}`  | Remove a member, or cancel or decline.    |
| `PUT`    | `/api/groups/{group}/membership`        | Join a group you manage.                  |
| `DELETE` | `/api/groups/{group}/membership`        | Leave a group yourself.                   |
| `PUT`    | `/api/groups/{group}/request`           | Ask to join a group.                      |
| `DELETE` | `/api/groups/{group}/request`           | Withdraw a request to join.               |
| `GET`    | `/api/invitations`                      | List the invitations sent to you.         |
| `POST`   | `/api/invitations/{invitation}/accept`  | Accept an invitation.                     |
| `POST`   | `/api/invitations/{invitation}/decline` | Decline an invitation.                    |
| `GET`    | `/api/invite-links/{code}`              | See where an invite link leads.           |
| `POST`   | `/api/invite-links/{code}/follow`       | Follow an invite link.                    |
| `POST`   | `/api/invite-links/{code}/welcome`      | Follow an invite link as a new guest.     |
| `GET`    | `/api/{kind}/{id}/grants`              | List who holds a resource you own.        |
| `PUT`    | `/api/{kind}/{id}/grants/{person|group|system}/{id}` | Grant someone access.   |
| `DELETE` | `/api/{kind}/{id}/grants/{person|group|system}/{id}` | Withdraw someone's access. |
| `GET`    | `/api/{kind}/{id}/invite-link`         | See a resource's invite link.             |
| `PUT`    | `/api/{kind}/{id}/invite-link`         | Give a resource an invite link.           |
| `POST`   | `/api/{kind}/{id}/invite-link`         | Replace a resource's invite link.         |
| `DELETE` | `/api/{kind}/{id}/invite-link`         | Turn off a resource's invite link.        |

## 🤝 Contributing

Hecate is developed as part of a larger private project, of which this repository is an automatically synchronised
mirror (by [GitHub Graph](https://github.com/SgtSwagrid/github-graph)), so changes made here directly would be overwritten.
Issues are very welcome; for anything more, please open an issue first.

## 👁️ See also

- [Eunomia](https://github.com/SgtSwagrid/eunomia), a sibling, for filtering, ordering and paging lists.
- [Iris](https://github.com/SgtSwagrid/iris), a sibling, a provider-agnostic client for large language models.
- [Dike](https://github.com/SgtSwagrid/dike), a sibling, for ranking by pairwise comparison.
- [qr4s](https://github.com/SgtSwagrid/qr4s), a sibling, for generating QR codes, on the JVM and in the browser.
- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
