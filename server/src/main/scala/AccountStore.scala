package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{AuthRefusal, Principal, Resource}
import slick.dbio.DBIO

/**
  * A deleter of user accounts and everything that belongs to them: sessions,
  * recovery codes, the groups they alone own, memberships, invitations, grants
  * and the host's own rows, all in one transaction.
  *
  * @param tables
  *   The tables the accounts are stored in.
  *
  * @param db
  *   The database to run the deletion against.
  *
  * @param users
  *   The store of users.
  *
  * @param groups
  *   The store of groups, which deletes the groups a user alone owns.
  *
  * @param grants
  *   The store of grants.
  *
  * @param cascade
  *   The hook deleting the host's rows for the user being deleted. It runs
  *   inside the deletion's transaction, before the check for resources the user
  *   alone owns, so that what it deletes is no reason to refuse; a refusal
  *   rolls it back.
  */
final class AccountStore
  (
    tables: AuthTables,
    db: Transactor,
    users: UserStore,
    groups: GroupStore,
    grants: GrantStore,
    cascade: Seq[Principal] => DBIO[Unit],
  ):

  import tables.profile.api.*

  /**
    * Deletes an account and everything that belongs to it.
    *
    * Fails with an [[AuthProblem]], deleting nothing, while the user together
    * with the groups their account takes ([[GroupStore.soleGroupsOf]]) are the
    * only owners of a resource other than those groups (see
    * [[GrantStore.ownedSolelyBy]]). A group someone else owns too survives. The
    * user's row is locked first, as [[GrantStore.grant]] locks it too, so a
    * concurrent grant to the user either commits first and is withdrawn, or
    * waits.
    *
    * @param user
    *   The identifier of the user to delete.
    */
  def delete(user: Long): IO[Unit] = db
    .run(deleting(user)(DBIO.successful(Option.empty[Unit])).transactionally)
    .void

  /**
    * Deletes an account at its own user's request, as [[delete]] does, unless
    * anyone manages it (holds a grant over [[Resource.user]]): deleting a
    * managed account is its managers' alone, and is refused as
    * [[AuthRefusal.AccountManaged]].
    *
    * @param user
    *   The identifier of the user to delete.
    */
  def deleteOwn(user: Long): IO[Unit] = db
    .run(
      deleting(user)(
        grants
          .granted(Resource.user(user))
          .flatMap(held =>
            refuseUnless(
              held.isEmpty,
              AuthRefusal.AccountManaged,
            ),
          )
          .map(_ => Option.empty[Unit]),
      ).transactionally,
    )
    .void

  /**
    * Deletes an account unless a check, read under its user's row lock before
    * anything is written, objects, in the caller's transaction. The lock guards
    * the grants over the account too.
    *
    * @return
    *   An action producing the check's objection, deleting nothing, or `None`
    *   once the account is deleted.
    */
  private[server] def deleting[X]
    (user: Long)
    (check: DBIO[Option[X]])
    : DBIO[Option[X]] =
    for
      _         <- tables.lockUser(user)
      objection <- check
      _         <- objection.fold(deleted(user))(_ => DBIO.unit)
    yield objection

  private def deleted(user: Long): DBIO[Unit] =
    for
      doomed <- groups.soleGroupsOf(user)
      _      <- cascade(Seq(Principal.Person(user)))
      _      <-
        refuseOrphans(Principal.Person(user) +: doomed.map(Principal.Group(_)))
      _ <- forget(user, doomed)
    yield ()

  private def refuseOrphans(doomed: Seq[Principal]): DBIO[Unit] = grants
    .ownedSolelyBy(doomed)
    .map(_.filterNot(_.kind == Resource.groupKind))
    .flatMap(orphaned =>
      refuseUnless(
        orphaned.isEmpty,
        AuthRefusal.SoleOwner(orphaned.size),
      ),
    )

  private def forget(user: Long, doomed: Seq[Long]): DBIO[Unit] =
    for
      _ <- groups.forget(user, doomed)
      _ <- grants.revokeHeldBy(Seq(Principal.Person(user)))
      _ <- grants.revokeOver(Resource.user(user))
      _ <- users.delete(user)
    yield ()
