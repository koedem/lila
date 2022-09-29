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

  def render(frag: Frag, asks: Iterable[Option[Ask]])(implicit ctx: Context): Frag =
    if (asks.isEmpty) frag
    else
      RawFrag(
        AskApi.bake(
          frag.render,
          asks.map {
            case Some(ask) =>
              div(cls := s"ask-container${ask.isStretch ?? " stretch"}", renderInner(ask)) render
            case None =>
              AskApi.askNotFoundFrag
          }
        )
      )

  def renderInner(ask: Ask)(implicit ctx: Context): Frag =
    fieldset(cls := "ask", id := ask._id, hasValue(ask) option (value := ""))(
      header(ask),
      RenderType(ask) match {
        case POLL    => pollBody(ask)
        case RANK    => rankBody(ask)
        case QUIZ    => quizBody(ask)
        case BAR     => barGraphBody(ask)
        case RANKBAR => rankGraphBody(ask)
      },
      footer(ask)
    )

  private def header(ask: Ask)(implicit ctx: Context): Frag =
    legend(cls := "ask__header")(
      label(ask.question),
      ctx.me.exists(_ is ask.creator) option
        a(
          href     := s"${routes.Ask.admin(ask.creator)}#${ask._id}",
          title    := trans.edit.txt(),
          dataIcon := '\ue019'
        )
    )

  private def footer(ask: Ask)(implicit ctx: Context): Frag =
    ask.isFeedback || (ask.isQuiz && getPick(ask).nonEmpty && ask.footer.nonEmpty) option
      div(cls := "ask__footer")(
        ask.footer map (label(_)),
        ask.isFeedback && !ask.isConcluded option ctx.me.fold(emptyFrag)(u =>
          div(
            input(
              cls         := "feedback",
              tpe         := "text",
              maxlength   := 80,
              placeholder := "80 characters max",
              value       := ask.feedbackFor(u.id)
            ),
            div(cls := "ask__submit")(input(cls := "button", tpe := "button", value := "Submit"))
          )
        )
      )

  private def pollBody(ask: Ask)(implicit ctx: Context): Frag = frag {
    val pick = getPick(ask)
    choiceContainer(ask)(
      ask.choices.zipWithIndex map { case (choiceText, i) =>
        div(
          cls := s"exclusive-choice ${if (pick.contains(i)) "selected" else "enabled"}${ask.isStretch ?? " stretch"}",
          title := tooltip(ask, choiceText.some),
          value := i
        )(label(choiceText))
      }
    )
  }

  private def rankBody(ask: Ask)(implicit ctx: Context): Seq[Frag] = Seq[Frag](
    choiceContainer(ask)(
      validRanking(ask).zipWithIndex map { case (choice, index) =>
        div(
          cls                                := s"ranked-choice${ask.isStretch ?? " stretch"}",
          value                              := choice,
          ctx.me.isDefined option (draggable := "true")
        )(div(cls := "rank-badge")(s"${index + 1}"), label(ask.choices(choice)))
      }
    )
  )

  private def quizBody(ask: Ask)(implicit ctx: Context): Frag = {
    val pick = getPick(ask)
    choiceContainer(ask)(
      ask.choices.zipWithIndex map { case (choiceText, i) =>
        val classes =
          if (pick isEmpty) "exclusive-choice enabled"
          else if (ask.answer map (a => ask.choices indexOf a) contains i) "choice correct"
          else if (pick contains i) "choice wrong"
          else "choice disabled"
        div(
          title := tooltip(ask, choiceText.some),
          cls   := s"$classes${ask.isStretch ?? " stretch"}",
          value := i
        )(label(choiceText))
      }
    )
  }

  def barGraphBody(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask__bar-graph")(
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

  def rankGraphBody(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask__bar-graph")(
      table(
        tbody(frag {
          ask.averageRank.zipWithIndex.sortWith((i, j) => i._1 < j._1) map { case (avgIndex, choice) =>
            val lastIndex  = ask.choices.size - 1
            val pct        = (lastIndex - avgIndex) / lastIndex * 100
            val choiceText = ask.choices(choice)
            tr(
              title := tooltip(ask, choiceText.some),
              td(choiceText),
              td,
              td(div(cls := "bar", css("width") := s"$pct%")(nbsp))
            )
          }
        })
      )
    )

  private def choiceContainer(ask: Ask): scalatags.Text.TypedTag[String] = {
    val sb = new StringBuilder("ask__choices")
    if (ask.isVertical) sb ++= " vertical"
    if (ask.isStretch) sb ++= " stretch"
    else if (ask.isCenter) sb ++= " center" // stretch overrides center
    div(cls := sb.toString)
  }

  def tooltip(ask: Ask, choice: Option[String])(implicit ctx: Context): String =
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

        case _ =>
      }
      if (sb.isEmpty) choiceText else sb.toString
    }

  private def getPick(ask: Ask)(implicit ctx: Context): Option[Int] =
    ctx.me.flatMap(u => ask.picks.flatMap(_ get u.id) flatMap (_ headOption))

  private def getRanking(ask: Ask)(implicit ctx: Context): Option[Vector[Int]] =
    ctx.me.flatMap(u => ask.picks.flatMap(_ get u.id))

  private def hasValue(ask: Ask)(implicit ctx: Context): Boolean =
    ctx.me.exists(u => (ask.hasPickFor(u.id) || ask.hasFeedbackFor(u.id)))

  private def pluralize(item: String, n: Int): String =
    if (n == 0) s"No ${item}s" else if (n == 1) s"1 ${item}" else s"$n ${item}s"

  private def whoPicked(ask: Ask, choice: String, prefix: Boolean): String = {
    val who = ask.whoPicked(choice)
    who.take(10).mkString(prefix ?? ": ", " ", (who.length > 10) ?? ", and others...")
  }

  private def validRanking(ask: Ask)(implicit ctx: Context): Vector[Int] = {
    val initialOrder = (0 until ask.choices.size).toVector
    getRanking(ask).fold(initialOrder) { r =>
      if (r == Nil || r.distinct.sorted != initialOrder) {
        // it's late to be doing this but i think it beats counting the choices in an
        // aggregation stage in every db update or storing choices.size in a redundant field
        ctx.me.map(u => env.ask.api.setPicks(ask._id, u.id, Some(Nil))) // blow away the bad
        initialOrder
      } else r
    }
  }

  sealed abstract class RenderType()
  object RenderType {
    case object POLL    extends RenderType
    case object RANK    extends RenderType
    case object QUIZ    extends RenderType
    case object BAR     extends RenderType
    case object RANKBAR extends RenderType
    def apply(ask: Ask): RenderType =
      if (ask.isQuiz) QUIZ
      else if (ask.isRanked) if (ask.isConcluded) RANKBAR else RANK
      else if (ask.isConcluded) BAR
      else POLL
  }
}
