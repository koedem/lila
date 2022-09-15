package views.html

import scala.collection.mutable

import controllers.routes
import lila.api.Context
import lila.app.templating.Environment._
import lila.app.ui.ScalatagsTemplate._
import lila.ask.Ask
import lila.ask.AskApi
import lila.security.{ Granter, Permission }

object ask {
  import RenderType._

  def report(asks: List[Ask], user: lila.common.LightUser)(implicit ctx: Context): Frag = {
    views.html.base.layout(
      title = s"${user.titleName} polls",
      moreJs = frag(
        jsModule("ask")
      ),
      moreCss = frag(
        cssTag("ask")
      ),
      csp = defaultCsp.withInlineIconFont.some
    ) {
      main(cls := "ask page-small box box-pad")(
        h1(
          s"${user.titleName} polls"
        ),
        div(cls := "ask__report")(
          asks map renderInner
        )
      )
    }
  }

  def render(frag: Frag, asks: Iterable[Option[Ask]])(implicit ctx: Context): Frag =
    if (asks.isEmpty) frag
    else RawFrag(
      AskApi.renderAsks(frag.render, asks.map {
        case Some(ask) => renderOuter(ask) render
        case None => AskApi.askNotFoundFrag
      })
    )

  def renderInner(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask", id := ask._id)(
      div(cls := "ask-header")(
        p(cls := "ask-question")(ask.question),
        if (!ask.isConcluded && ctx.me.exists(_.id == ask.creator))
          div(cls := "ask-actions")(
            if (ask.answer.isEmpty)
              button(cls := "button ask-action ask-xhr", formaction := routes.Ask.conclude(ask._id))(
                "Conclude"
              ),
            button(cls := "button ask-action ask-xhr", formaction := routes.Ask.reset(ask._id))(
              "Reset"
            )
          )
      ),
      RenderType(ask) match {
        case QUIZ => quizChoices(ask)
        case POLL => pollChoices(ask)
        case RANKED => rankChoices(ask)
        case BAR  => barGraph(ask)
      },
      footer(ask)
    )

  private def renderOuter(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask-container")(
      renderInner(ask)
    )

  private def quizChoices(ask: Ask)(implicit ctx: Context): Frag = frag {
    val pick = getPick(ask)
    val ans  = ask.answer map (a => ask.choices.indexOf(a))

    choiceContainer("ask-choices", ask)(
      ask.choices.zipWithIndex.map { case (choiceText, i) =>
        div(
          ask.isStretch option (style := "flex: auto !important;"),
          title := tooltip(ask, choiceText.some),
          button(
            pick.isEmpty option (cls := "ask-xhr"),
            st.id                    := s"${ask._id}_$i",
            formaction               := routes.Ask.pick(ask._id, i)
          ),
          label(
            `for` := s"${ask._id}_$i",
            if (pick.isEmpty) cls := "ask-enabled"
            else if (ans.contains(i)) cls := "ask-correct"
            else if (pick.contains(i)) cls := "ask-wrong"
            else cls                       := "ask-disabled" // this comment to prevent bizarre scalafmtness
          )(choiceText)
        )
      }
    )
  }

  private def pollChoices(ask: Ask)(implicit ctx: Context): Frag = frag {
    val pick = getPick(ask)
    choiceContainer("ask-choices", ask)(
      ask.choices.zipWithIndex.map { case (choiceText, i) =>
        val formPick = if (pick contains i) -1 else i
        val id       = s"${ask._id}_$i"
        div(
          ask.isStretch option (style := "flex: auto !important;"),
          title := tooltip(ask, choiceText.some),
          button(
            cls        := s"ask-xhr",
            st.id      := id,
            formaction := routes.Ask.pick(ask._id, formPick) // -1 unsets vote
          ),
          label(`for` := id, cls := (if (pick.contains(i)) "ask-vote" else "ask-enabled"))(choiceText)
        )
      }
    )
  }

  private def rankChoices(ask: Ask)(implicit ctx: Context): Frag =
    choiceContainer("ask-ranked", ask)(
      validRanking(ask) map { choice =>
        div(
          cls := "ask-ranked-choice",
          value := choice,
          draggable := "true",
          ask.isStretch option (style := "flex: auto !important;")
        )(ask.choices(choice - 1))
      }
    )

  private def footer(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask-footer")(
      if (ask.isFeedback) div(
        p(cls := "ask-prompt")(~ask.footer),
        div(cls := "ask-footer-box")(
          input(cls := "ask-text-field", tpe := "text", maxlength := 80, placeholder := "80 characters max")(
            ask.getFeedback(ctx.me.get.id)
          ),
          input(cls := "ask-submit button", tpe := "submit")("Submit")
        )
      )
      else div(cls := "ask-footer-box")(
        p(cls := "ask-prompt")(!ask.isQuiz || getPick(ask).nonEmpty option ask.footer),
        ask.isRanked option input(cls := "ask-submit button", tpe := "submit")("Submit")
      )
    )

  private def barGraph(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask-bar-graph", id := ask._id)(
      table(
        tbody(frag {
          val countMap = ask.choices.indices.map(i => (ask.choices(i), ask.count(i))).toMap
          val countMax = countMap.values.max
          countMap.toSeq.sortBy(_._2)(Ordering.Int.reverse).map { case (choiceText, count) =>
            val pct = if (countMax == 0) 0 else count * 100 / countMax
            tr(
              title := tooltip(ask, choiceText.some),
              td(choiceText),
              td(pluralize("vote", count)),
              td(div(cls := "ask-votes-bar", css("width") := s"$pct%")(nbsp))
            )
          }
        })
      )
    )

  private def choiceContainer(clas: String, ask: Ask) =
    div(
      cls := clas,
      ask.isVertical option (style := "flex-flow: column !important;"),
      ask.isStretch option (style := "align-items: stretch !important;"),
    )

  private def tooltip(ask: Ask, choice: Option[String])(implicit ctx: Context): String =
    choice ?? { choiceText =>
      val sb        = new mutable.StringBuilder(256);
      val pick      = getPick(ask)
      val count     = ask.count(choiceText)
      val hasChoice = pick.nonEmpty
      val isAuthor  = ctx.me.exists(_.id == ask.creator)
      val isShusher = ctx.me ?? Granter(Permission.Shusher)

      RenderType(ask) match {
        case BAR =>
          sb ++= pluralize("vote", count)
          if (ask.isPublic || isShusher)
            sb ++= whoPicked(ask, choiceText, prefix = true)

        case QUIZ =>
          if (ask.isTally && hasChoice || isAuthor || isShusher)
            sb ++= pluralize("pick", count)
          if ((hasChoice || isAuthor) && ask.isPublic || isShusher)
            sb ++= whoPicked(ask, choiceText, sb.nonEmpty)

        case POLL =>
          if (isAuthor || ask.isTally)
            sb ++= pluralize("vote", count)
          if (ask.isPublic && ask.isTally || isShusher)
            sb ++= whoPicked(ask, choiceText, sb.nonEmpty)

        case RANKED =>

      }
      if (sb.isEmpty) choiceText else sb.toString
  }

  private def getPick(ask: Ask)(implicit ctx: Context): Option[Int] =
    ctx.me.flatMap(u => ask.picks.flatMap(_ get u.id) flatMap(_ headOption))

  private def getRanking(ask: Ask)(implicit ctx: Context): Option[List[Int]] =
    ctx.me.flatMap(u => ask.picks.flatMap(_ get u.id))

  private def pluralize(item: String, n: Int): String =
    if (n == 0) s"No ${item}s" else if (n == 1) s"1 ${item}" else s"$n ${item}s"

  private def whoPicked(ask: Ask, choice: String, prefix: Boolean): String = {
    val who = ask.whoPicked(choice)
    who.take(10).mkString(prefix ?? ": ", " ", (who.length > 10) ?? ", and others...")
  }

  private def validRanking(ask: Ask)(implicit ctx: Context): List[Int] = {
    val initialOrder = (1 to ask.choices.length).toList
    getRanking(ask).fold(initialOrder) { r =>
      if (r == Nil || r.distinct.sorted != initialOrder) {
        // i hate to do this here but it beats counting the choices as a
        // separate aggregation stage in every db update, or storing the size in a separate field
        ctx.me.map(u => env.ask.api.pick(ask._id, u.id, None)) // blow away the bad value
        initialOrder
      } else r
    }
  }

  sealed abstract class RenderType()
  object RenderType {
    case object POLL extends RenderType
    case object RANKED extends RenderType
    case object QUIZ extends RenderType
    case object BAR  extends RenderType
    def apply(ask: Ask): RenderType =
      if (ask.isQuiz) QUIZ else if (ask.isConcluded) BAR else if (ask.isRanked) RANKED else POLL
  }
}
