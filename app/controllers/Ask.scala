package controllers

import lila.app._
import play.api.data.Form
import play.api.data.Forms.single

final class Ask(env: Env) extends LilaController(env) {

  def submit(id: String, picks: Option[String]) =
    AuthBody { implicit ctx => me =>
      implicit val req = ctx.body
      // don't validate picks here, otherwise 'valid' junk picks are filtered at view creation
      env.ask.api.update(
        id,
        me.id,
        picks map (_ split ('-') filter (_ nonEmpty) map (_ toInt) toList),
        feedbackForm.bindFromRequest().value
      ) map {
        case Some(ask) => Ok(views.html.ask.renderInner(ask))
        case None      => NotFound(s"Ask $id not found")
      }
    }

  def conclude(id: String) = authorizedAction(id, env.ask.api.conclude)

  def reset(id: String) = authorizedAction(id, env.ask.api.reset)

  def byUser(uid: String) =
    AuthBody { implicit ctx => me =>
      for {
        user <- env.user.lightUser(uid)
        asks <- env.ask.api.byUser(uid)
        if user.nonEmpty
      } yield Ok(views.html.ask.report(asks, user.get))
    }

  private def authorizedAction(id: String, action: lila.ask.Ask => Fu[Option[lila.ask.Ask]]) =
    AuthBody { implicit ctx => me =>
      env.ask.api.get(id) flatMap {
        case None => fuccess(NotFound(s"Ask id ${id} not found"))
        case Some(ask) =>
          if (ask.creator != me.id) fuccess(Unauthorized)
          else
            action(ask) flatMap {
              case Some(newAsk) => fuccess(Ok(views.html.ask.renderInner(newAsk)))
              case None         => fufail(new RuntimeException("Something is so very wrong."))
            }
      }
    }
  private val feedbackForm =
    Form[String](single("text" -> lila.common.Form.cleanNonEmptyText(maxLength = 80)))
}
