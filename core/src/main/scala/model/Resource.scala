package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A resource that access may be granted over, named in the host's own terms,
  * or a group or an account, which the library names itself.
  *
  * @param kind
  *   The kind of the resource (e.g. `document`), chosen by the host, or
  *   [[Resource.groupKind]] for a group, or [[Resource.userKind]] for an
  *   account.
  *
  * @param id
  *   The identifier of the resource among those of its kind.
  */
final case class Resource(kind: String, id: Long) derives Codec.AsObject

object Resource:

  /**
    * The kind of a group as a resource, over which access decides who manages
    * it. Reserved: a host must not name a kind of its own this.
    */
  val groupKind: String = "group"

  /**
    * The kind of an account as a resource, over which access decides who
    * manages it. Reserved: a host must not name a kind of its own this.
    */
  val userKind: String = "user"

  /**
    * Names a group as a resource.
    *
    * @param id
    *   The identifier of the group.
    *
    * @return
    *   The group as a resource.
    */
  def group(id: Long): Resource = Resource(groupKind, id)

  /**
    * Names an account as a resource. A grant over it reaches its managers; its
    * own user's powers over it come from being signed in as it, never from a
    * grant.
    *
    * @param id
    *   The identifier of the account's user.
    *
    * @return
    *   The account as a resource.
    */
  def user(id: Long): Resource = Resource(userKind, id)
