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
      moreJs = jsModule("ask"),
      moreCss = cssTag("ask"),
      csp = defaultCsp.withInlineIconFont.some
    ) {
      main(cls := "page-small box box-pad")(
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
    else
      RawFrag(
        AskApi.renderAsks(
          frag.render,
          asks.map {
            case Some(ask) => div(cls := "ask-container", renderInner(ask)) render
            case None      => AskApi.askNotFoundFrag
          }
        )
      )

  def renderInner(ask: Ask)(implicit ctx: Context): Frag =
    fieldset(cls := "ask", id := ask._id)(
      legend(cls := "ask__header")(label(ask.question)),
      ask.choices.nonEmpty option div(cls := "ask__body")(
        RenderType(ask) match {
          case QUIZ   => quizChoices(ask)
          case POLL   => pollChoices(ask)
          case RANKED => rankChoices(ask)
          case BAR    => barGraph(ask)
        },
        (ask.isRanked && !ask.isFeedback) option submitBtn
      ),
      if (ask.isFeedback || (ask.isQuiz && getPick(ask).nonEmpty && ask.footer.nonEmpty))
        footer(ask)
    )

  private val submitBtn = div(cls := "ask__submit")(
    input(cls := "button", tpe := "button", value := "Submit")
  )

  private def quizChoices(ask: Ask)(implicit ctx: Context): Frag = frag {
    val pick = getPick(ask)
    choiceContainer("exclusive", ask)(
      ask.choices.zipWithIndex map { case (choiceText, i) =>
        val prefix =
          if (pick isEmpty) "enabled xhr-"
          else if (ask.answer map (a => ask.choices indexOf a) contains i) "correct "
          else if (pick contains i) "wrong "
          else "disabled "
        div(
          title := tooltip(ask, choiceText.some),
          cls   := s"${prefix}choice${ask.isStretch ?? " stretch"}",
          value := i
        )(label(choiceText))
      }
    )
  }

  private def pollChoices(ask: Ask)(implicit ctx: Context): Frag = frag {
    val pick = getPick(ask)
    choiceContainer("exclusive", ask)(
      ask.choices.zipWithIndex map { case (choiceText, i) =>
        div(
          cls := s"xhr-choice ${if (pick.contains(i)) "selected" else "enabled"}${ask.isStretch ?? " stretch"}",
          title := tooltip(ask, choiceText.some),
          value := i
        )(label(choiceText))
      }
    )
  }

  private def rankChoices(ask: Ask)(implicit ctx: Context): Frag =
    choiceContainer("ranked", ask)(
      validRanking(ask) map { choice =>
        div(
          cls       := s"ranked-choice${ask.isStretch ?? " stretch"}",
          value     := choice,
          draggable := "true"
        )(label(ask.choices(choice - 1)))
      }
    )

  private def footer(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask__footer")(
      ask.footer map (label(_)),
      ask.isFeedback option div(
        input(
          cls         := "feedback",
          tpe         := "text",
          maxlength   := 80,
          placeholder := "80 characters max",
          value       := ask.getFeedback(ctx.me.get.id)
        ),
        submitBtn
      )
    )

  private def choiceContainer(clazz: String, ask: Ask) =
    div(cls := s"ask__choices ${ask.isVertical ?? "vertical"} ${ask.isStretch ?? "stretch"}")

  private def barGraph(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask__bar-graph", id := ask._id)(
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
              td(div(cls := "bar", css("width") := s"$pct%")(nbsp))
            )
          }
        })
      )
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
    ctx.me.flatMap(u => ask.picks.flatMap(_ get u.id) flatMap (_ headOption))

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
        ctx.me.map(u =>
          env.ask.api.update(ask._id, u.id, Some(Nil), ask.getFeedback(u.id))
        ) // blow away the bad value
        initialOrder
      } else r
    }
  }

  sealed abstract class RenderType()
  object RenderType {
    case object POLL   extends RenderType
    case object RANKED extends RenderType
    case object QUIZ   extends RenderType
    case object BAR    extends RenderType
    def apply(ask: Ask): RenderType =
      if (ask.isQuiz) QUIZ else if (ask.isConcluded) BAR else if (ask.isRanked) RANKED else POLL
  }
}
