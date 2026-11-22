package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.{Holder, InviteResult, InviteStatus}

/**
  * What inviting someone or a group to a group did.
  *
  * @param invitee
  *   The person or group invited, named as stored.
  *
  * @param invitation
  *   The identifier of the invitation sent, or `None` where none was: they were
  *   already invited or a member, or joined at once.
  *
  * @param status
  *   Whether they were invited, joined at once, or were a member already.
  */
final case class Offer
  (
    invitee: Holder,
    invitation: Option[Long],
    status: InviteStatus,
  ):

  /** The result to tell the inviter. */
  def result: InviteResult = InviteResult(invitee, status)
