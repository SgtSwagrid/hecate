package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.model.{LinkPreview, LinkTarget}
import com.raquo.laminar.api.L.*
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * A browser-side driver of the endpoints of `LinkApi`, previewing and
  * following invite links. Nothing here subscribes on its own.
  *
  * @param auth
  *   The sign-in state, rechecked on a refusal.
  *
  * @param groups
  *   The state of the user's groups, refreshed once a link is followed.
  */
final class InviteLinks(auth: AuthState, groups: GroupsState):

  /**
    * Shows where an invite link leads.
    *
    * @param code
    *   The code of the link.
    *
    * @return
    *   A stream asking when followed, emitting the preview or a refusal.
    */
  def preview(code: String): EventStream[Either[String, LinkPreview]] = auth
    .explained[LinkPreview](Fetch.get(path(code)))

  /**
    * Follows an invite link, giving the user what it leads to.
    *
    * @param code
    *   The code of the link.
    *
    * @return
    *   A stream following the link when followed, emitting its target or a
    *   refusal.
    */
  def follow(code: String): EventStream[Either[String, LinkTarget]] = auth
    .explained[LinkTarget](Fetch.post(s"${ path(code) }/follow"))
    .map: outcome =>
      if outcome.isRight then groups.refresh()
      outcome

  private def path(code: String): String = s"/api/invite-links/$code"
