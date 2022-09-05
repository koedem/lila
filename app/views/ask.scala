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

  def testRanked()(implicit ctx: Context): Frag =
    div(cls := "ask-container")(
      div(cls := "ask")(
        ol(cls := "ask-ranked")(
          li(cls := "ask-ranked-choice", value := "1", draggable:="true")("Red Ivy"),
          li(cls := "ask-ranked-choice", value := "2", draggable:="true")("Nefertiti"),
          li(cls := "ask-ranked-choice", value := "3", draggable:="true")("Patricia Araujo"),
          li(cls := "ask-ranked-choice", value := "4", draggable:="true")("Lisa Kage"),
          li(cls := "ask-ranked-choice", value := "5", draggable:="true")("Suzanna Holmes"),
        )
      )
    )
    
  def render(frag: Frag, asks: Iterable[Ask])(implicit ctx: Context) =
    if (asks.isEmpty)
      frag
    else
      RawFrag(
        AskApi.renderAsks(frag.render, asks.map(renderContainer(_) render))
      )

  def renderInner(ask: Ask)(implicit ctx: Context) =
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
        case BAR  => barGraph(ask)
      },
      ask.reveal match {
        case Some(reveal) if getPick(ask).nonEmpty => div(cls := "ask-reveal")(p(reveal))
        case _                                     => emptyFrag
      }
    )

  private def renderContainer(ask: Ask)(implicit ctx: Context) =
    div(cls := "ask-container")(
      renderInner(ask)
    )

  private def quizChoices(ask: Ask)(implicit ctx: Context) = frag {
    val pick = getPick(ask)
    val ans  = ask.answer map (a => ask.choices.indexOf(a))

    div(cls := "ask-choices")(
      ask.choices.zipWithIndex.map { case (choiceText, i) =>
        div(
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

  private def pollChoices(ask: Ask)(implicit ctx: Context) = frag {
    val pick = getPick(ask)
    div(cls := "ask-choices")(
      ask.choices.zipWithIndex.map { case (choiceText, i) =>
        val formPick = if (pick.exists(_ == i)) -1 else i
        val id       = s"${ask._id}_$i"
        div(
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

  private def barGraph(ask: Ask)(implicit ctx: Context) = {
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
  }

  private def tooltip(ask: Ask, choice: Option[String])(implicit ctx: Context): String = choice match {
    case None => ""

    case Some(choiceText) =>
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
            sb ++= whoPicked(ask, choiceText, true)

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
      }
      if (sb.isEmpty) choiceText else sb.toString
  }

  private def getPick(ask: Ask)(implicit ctx: Context): Option[Int] =
    ctx.me.flatMap(u => ask.picks.flatMap(p => p.get(u.id)))

  private def pluralize(item: String, n: Int): String =
    if (n == 0) s"No ${item}s" else if (n == 1) s"1 ${item}" else s"$n ${item}s"

  private def whoPicked(ask: Ask, choice: String, prefix: Boolean): String = {
    val who = ask.whoPicked(choice)
    who.take(10).mkString(prefix ?? ": ", " ", (who.length > 10) ?? ", and others...")
  }

  sealed abstract class RenderType()
  object RenderType {
    case object POLL extends RenderType
    case object QUIZ extends RenderType
    case object BAR  extends RenderType
    def apply(ask: Ask): RenderType =
      if (ask.isQuiz) QUIZ else if (ask.isConcluded) BAR else POLL
  }
}
