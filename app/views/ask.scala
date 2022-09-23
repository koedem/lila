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
          asks map { ask =>
            div(
              table(
                th(td, td, td),
                tr(td("id:", ask._id)),
                tr(td("question:", ask.question)),
                ask.url map { url =>
                  tr(td("url:", url))
                },
                tr(td("created:"), ask.createdAt.toString()),
                ask.answer map (a => tr(td("answer:"), td(a))),
                ask.footer map (f => tr(td("footer:"), td(f))),
                ask.choices map { c =>
                  tr(td(c), td(ask.count(c)), td(ask.whoPicked(c)))
                },
                ask.feedback.fold(emptyFrag)(x =>
                  for ((uid, fb) <- x) {
                    tr(td, td(uid), td(fb))
                  }
                )
              ),
              //barGraph(ask),
              renderInner(ask)
            )
          }
        )
      )
    }
  }

  def render(frag: Frag, asks: Iterable[Option[Ask]])(implicit ctx: Context): Frag =
    if (asks.isEmpty) frag
    else
      RawFrag(
        AskApi.bake(
          frag.render,
          asks.map {
            case Some(ask) => div(cls := "ask-container", renderInner(ask)) render
            case None => AskApi.askNotFoundFrag
          }
        )
      )

  def renderInner(ask: Ask)(implicit ctx: Context): Frag =
    fieldset(cls := "ask", id := ask._id, hasValue(ask) option (value := ""))(
      header(ask),
      body(ask),
      footer(ask)
    )

  private def header(ask: Ask)(implicit ctx: Context): Frag =
    legend(cls := "ask__header")(
      label(ask.question),
      ctx.me.exists(_ is ask.creator) option
        a(href := routes.Ask.byUser(ask.creator), title := trans.edit.txt(), dataIcon := '\ue019')
    )

  private def body(ask: Ask)(implicit ctx: Context): Frag =
    ask.choices.nonEmpty option div(cls := "ask__body")(
      RenderType(ask) match {
        case QUIZ => quizChoices(ask)
        case POLL => pollChoices(ask)
        case RANK => rankChoices(ask)
        case BAR => barGraph(ask)
        case RANKBAR => rankGraph(ask)
      },
    )

  // if it's a quiz, only show the footer if the answer has been attempted. if not,
  // only show the footer if it's a feedback prompt. otherwise footer text is ignored
  private def footer(ask: Ask)(implicit ctx: Context): Frag =
    ask.isFeedback || (ask.isQuiz && getPick(ask).nonEmpty && ask.footer.nonEmpty) option
      div(cls := "ask__footer")(
        ask.footer map (label(_)),
        ask.isFeedback && !ask.isConcluded option ctx.me.fold(emptyFrag)(u =>
          div(
            input(
              cls := "feedback",
              tpe := "text",
              maxlength := 80,
              placeholder := "80 characters max",
              value := ask.feedbackFor(u.id)
            ),
            submitBtn(ask)
          )
        )
      )

  private def quizChoices(ask: Ask)(implicit ctx: Context): Frag = {
    val pick = getPick(ask)
    choiceContainer(ask)(
      ask.choices.zipWithIndex map { case (choiceText, i) =>
        val prefix =
          if (pick isEmpty) "enabled xhr-"
          else if (ask.answer map (a => ask.choices indexOf a) contains i) "correct "
          else if (pick contains i) "wrong "
          else "disabled "
        div(
          title := tooltip(ask, choiceText.some),
          cls := s"${prefix}choice${ask.isStretch ?? " stretch"}",
          value := i
        )(label(choiceText))
      }
    )
  }

  private def pollChoices(ask: Ask)(implicit ctx: Context): Frag = frag {
    val pick = getPick(ask)
    choiceContainer(ask)(
      ask.choices.zipWithIndex map { case (choiceText, i) =>
        div(
          cls := s"xhr-choice ${if (pick.contains(i)) "selected" else "enabled"}${ask.isStretch ?? " stretch"}",
          title := tooltip(ask, choiceText.some),
          value := i
        )(label(choiceText))
      }
    )
  }

  private def rankChoices(ask: Ask)(implicit ctx: Context): Seq[Frag] = Seq[Frag](
    choiceContainer(ask)(
      validRanking(ask) map { choice =>
        div(
          cls := s"ranked-choice${ask.isStretch ?? " stretch"}",
          value := choice,
          draggable := "true"
        )(label(ask.choices(choice)))
      }
    ),
    (!ask.isFeedback && !ask.isConcluded) option submitBtn(ask)
  )

  private def barGraph(ask: Ask)(implicit ctx: Context): Frag =
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

  private def rankGraph(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask__bar-graph")(
      table(
        tbody(frag {
          ask.averageRank.zipWithIndex.sortWith((i, j) => i._1 < j._1) map {
            case (avgIndex, choice) =>
              val lastIndex = ask.choices.size - 1
              val pct = (lastIndex - avgIndex) / lastIndex * 100
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

  private def submitBtn(ask: Ask)(implicit ctx: Context): Frag =
    ctx.me.fold(emptyFrag)(u =>
      div(cls := s"ask__submit${(ask.isRanked && !ask.hasPickFor(u.id)) ?? " dirty"}")(
        input(cls := "button", tpe := "button", value := "Submit")
      )
    )

  private def choiceContainer(ask: Ask): scalatags.Text.TypedTag[String] =
    div(cls := s"ask__choices ${ask.isVertical ?? "vertical"} ${ask.isStretch ?? "stretch"}")

  private def tooltip(ask: Ask, choice: Option[String])(implicit ctx: Context): String =
    choice ?? { choiceText =>
      val sb = new mutable.StringBuilder(256);
      val pick = getPick(ask)
      val count = ask.count(choiceText)
      val hasChoice = pick.nonEmpty
      val isAuthor = ctx.me.exists(_.id == ask.creator)
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
        // it's a little late for this but i think it beats counting the choices in an
        // aggregation stage in every db update or storing choices.size in a redundant field
        ctx.me.map(u =>
          env.ask.api.update(ask._id, u.id, Some(Nil), ask.feedbackFor(u.id))
        ) // blow away the bad value
        initialOrder
      } else r
    }
  }

  sealed abstract class RenderType()
  object RenderType {
    case object POLL   extends RenderType
    case object RANK   extends RenderType
    case object QUIZ   extends RenderType
    case object BAR    extends RenderType
    case object RANKBAR extends RenderType
    def apply(ask: Ask): RenderType =
      if (ask.isQuiz) QUIZ
      else if (ask.isRanked) if (ask.isConcluded) RANKBAR else RANK
      else if (ask.isConcluded) BAR else POLL
  }
}
