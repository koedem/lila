package controllers

import lila.app.{ given, * }
import play.api.data.Form
import play.api.data.Forms.single

final class Ask(env: Env) extends LilaController(env) {

  def picks(id: String, picks: Option[String], view: Option[String]) =
    AuthBody { implicit ctx => me =>
      // don't validate picks here, parseable but illegal picks are handled elsewhere
      env.ask.api.setPicks(
        id,
        me.id,
        paramToList(picks)
      ) map {
        case Some(ask) => Ok(views.html.ask.renderInner(ask, paramToList(view)))
        case None      => NotFound(s"Ask $id not found")
      }
    }

  def feedback(id: String, view: Option[String]) =
    AuthBody { implicit ctx => me =>
      implicit val req = ctx.body
      env.ask.api.setFeedback(id, me.id, feedbackForm.bindFromRequest().value) map {
        case Some(ask) => Ok(views.html.ask.renderInner(ask, paramToList(view)))
        case None      => NotFound(s"Ask $id not found")
      }
    }

  def unset(id: String, view: Option[String]) =
    AuthBody { implicit ctx => me =>
      env.ask.api.unset(id, me.id) map {
        case Some(ask) => Ok(views.html.ask.renderInner(ask, paramToList(view)))
        case None      => NotFound(s"Ask $id not found")
      }
    }

  /*def action(askAction: () => Fu[Option[lila.ask.Ask]]) =
    AuthBody { implicit ctx => me =>
    askAction() map {
      case Some(ask) => Ok(views.html.ask.renderInner(ask))
      case None => NotFound("Not found")
    }}*/

  def admin(id: String) =
    AuthBody { implicit ctx => me =>
      env.ask.api.unset(id, me.id) map {
        case Some(ask) => Ok(views.html.askAdmin.renderInner(ask))
        case None      => NotFound(s"Ask $id not found")
      }
    }

  def byUser(username: UserStr) =
    AuthBody { implicit ctx => me =>
      for {
        user <- env.user.lightUser(username.id)
        asks <- env.ask.api.byUser(username.id)
        if user.nonEmpty
      } yield Ok(views.html.askAdmin.show(asks, user.get))
    }

  def conclude(id: String) = authorizedAction(id, env.ask.api.conclude)

  def reset(id: String) = authorizedAction(id, env.ask.api.reset)

  def delete(id: String) =
    AuthBody { implicit ctx => me =>
      env.ask.api.get(id) flatMap {
        case None => fuccess(NotFound(s"Ask id ${id} not found"))
        case Some(ask) =>
          if (ask.creator != me.id) fuccess(Unauthorized)
          else {
            env.ask.api.delete(id)
            fuccess(Ok(lila.ask.AskApi.askNotFoundFrag))
          }
      }
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

  private def paramToList(param: Option[String]) =
    param map (_ split ('-') filter (_ nonEmpty) map (_ toInt) toList)

  private val feedbackForm =
    Form[String](single("text" -> lila.common.Form.cleanNonEmptyText(maxLength = 80)))
}
