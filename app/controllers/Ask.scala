package controllers

import lila.app._
import play.api.data.Form
import play.api.data.Forms.single

final class Ask(env: Env) extends LilaController(env) {

  def pick(id: String, pick: Int) =
    AuthBody { implicit ctx => me =>
      env.ask.api.pick(id, me.id, if (pick < 0) None else Some(pick)) map {
        case Some(ask) => Ok(views.html.ask.renderInner(ask))
        case None      => NotFound(s"Ask $id not found")
      }
    }

  def rank(id: String, ranking: String) =
    AuthBody { implicit ctx => me =>
      env.ask.api.rank(id, me.id, ranking.split('-').map(_ toInt).toList).void
    }

  def feedback(id: String) =
    AuthBody { implicit ctx => me =>
      implicit val req = ctx.body
      req.body.pp
      feedbackForm.bindFromRequest().fold(
        _ => BadRequest.fuccess,
        text =>
          env.ask.api.feedback(id, me.id, text) map {
            case Some(ask) =>
              Ok(views.html.ask.renderInner(ask))
            case None =>
              id.pp(s"$text ${me.id}")
              NotFound
          }
      )
    }

  def conclude(id: String) = authorizedAction(id, env.ask.api.conclude)

  def reset(id: String) = authorizedAction(id, env.ask.api.reset)

  def byUser(uid: String) =
    AuthBody { implicit ctx => me =>
      for {
        user <- env.user.lightUser(uid)
        asks <- env.ask.api.byUser(uid)
        if user.nonEmpty
      }
      yield Ok(views.html.ask.report(asks, user.get))
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
  private val feedbackForm = Form[String](single("text" -> lila.common.Form.cleanNonEmptyText(maxLength = 80)))
}
