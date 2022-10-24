package views.html

import scala.collection.mutable
import scala.util.Random.shuffle

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

  def renderInner(ask: Ask, prevView: Option[List[Int]] = None)(implicit ctx: Context): Frag = {
    val view = prevView getOrElse (if (ask.isRandom) shuffle(ask.choices.indices.toList) else ask.choices.indices.toList)
    fieldset(cls := "ask", id := ask._id, hasPick(ask) option (value := ""))(
      header(ask, view.nonEmpty ?? view.mkString("-")),
      ask.isConcluded option label(
        if (ask.choices.nonEmpty) s"${ask.picks ?? (_ size)} votes"
        else s"${ask.feedback ?? (_ size)} responses"
      ),
      if (ask.choices.isEmpty) emptyFrag
      else RenderType(ask) match {
        case POLL    => pollBody(ask, view)
        case RANK    => rankBody(ask, view)
        case QUIZ    => quizBody(ask, view)
        case BAR     => barGraphBody(ask)
        case RANKBAR => rankGraphBody(ask)
      },
      footer(ask)
    )
  }

  private def header(ask: Ask, viewParam: String)(implicit ctx: Context): Frag =
    legend(
      span(cls := "ask__header")(
        label(ask.question),
        button(
          cls := s"action unset${(hasPick(ask) && !ask.isConcluded) ?? " visible"}",
          formmethod := "POST",
          formaction := s"${routes.Ask.unset(ask._id, viewParam.some)}",
          title      := trans.delete.txt()
        ),
        ctx.me.exists(_ is ask.creator) option button(
          cls        := "action admin",
          formmethod := "GET",
          formaction := s"${routes.Ask.admin(ask._id)}",
          title      := trans.edit.txt()
        )
      )
    )

  private def footer(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask__footer")(
      // TODO jesus christ fix this boolean nightmare
      (ask.footer.nonEmpty && (!ask.isQuiz || getPick(ask).nonEmpty)) option ask.footer map (label(_)),
      ask.isFeedback && !ask.isConcluded option ctx.me.fold(emptyFrag) { u =>
        Seq(
          input(
            cls         := "feedback-text",
            tpe         := "text",
            maxlength   := 80,
            placeholder := "80 characters max",
            value       := ask.feedbackFor(u.id)
          ),
          div(cls := "feedback-submit")(input(cls := "button", tpe := "button", value := "Submit"))
        )
      },
      ask.isConcluded && ask.feedback.exists(_.size > 0) option frag {
        ask.feedback map { fbmap =>
          div(cls := "feedback-results")(
            ask.footer map (label(_)),
            fbmap.toSeq flatMap { case (uid, fb) => Seq(div(s"${ask.isPublic ?? s"$uid:"}"), div(fb)) }
          )
        }
      }
    )

  private def pollBody(ask: Ask, view: List[Int])(implicit ctx: Context): Frag = frag {
    val pick = getPick(ask)
    choiceContainer(ask) {
      (view.pp map ask.choices.zipWithIndex).pp("goodies") map { case (choiceText, choice) =>
        div(
          cls := s"choice exclusive${if (pick.contains(choice)) " selected" else " enabled"}${ask.isStretch ?? " stretch"}",
          title := tooltip(ask, choice),
          value := choice
        )(label(choiceText))
      }
    }
  }

  private def rankBody(ask: Ask, view: List[Int])(implicit ctx: Context): Seq[Frag] = Seq[Frag](
    choiceContainer(ask)(
      validRanking(ask).zipWithIndex map { case (choice, index) =>
        val clz = s"choice rank${ask.isStretch ?? " stretch"}${hasPick(ask) ?? " badge"}"
        div(cls := clz, value := choice, draggable := true)(
          div(s"${index + 1}"),
          label(ask.choices(choice)),
          i
        )
      }
    )
  )

  private def quizBody(ask: Ask, view: List[Int])(implicit ctx: Context): Frag = {
    val pick = getPick(ask)
    choiceContainer(ask)(
      view map ask.choices.zipWithIndex map { case (choiceText, choice) =>
        val classes =
          if (pick isEmpty) "choice exclusive enabled"
          else if (ask.answer map (a => ask.choices indexOf a) contains choice) "choice correct"
          else if (pick contains choice) "choice wrong"
          else "choice disabled"
        div(
          title := tooltip(ask, choice),
          cls   := s"$classes${ask.isStretch ?? " stretch"}",
          value := choice
        )(label(choiceText))
      }
    )
  }

  def barGraphBody(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask__graph")(frag {
      val countMap = ask.choices.indices.map(choice => (choice, ask.count(choice))).toMap
      val countMax = countMap.values.max
      countMap.toSeq.sortBy(_._2)(Ordering.Int.reverse).flatMap { case (choice, count) =>
        val pct  = if (countMax == 0) 0 else count * 100 / countMax
        val hint = tooltip(ask, choice)
        Seq(
          div(title := hint)(ask.choices(choice)),
          div(cls := "votes-text", title := hint)(pluralize("vote", count)),
          div(cls := "set-width", title := hint, css("width") := s"$pct%")(nbsp)
        )
      }
    })

  def rankGraphBody(ask: Ask)(implicit ctx: Context): Frag =
    div(cls := "ask__rank-graph")(frag {
      val tooltipVec = rankedTooltips(ask)
      ask.averageRank.zipWithIndex.sortWith((i, j) => i._1 < j._1) flatMap { case (avgIndex, choice) =>
        val lastIndex = ask.choices.size - 1
        val pct       = (lastIndex - avgIndex) / lastIndex * 100
        val hint      = tooltipVec(choice)
        Seq(
          div(title := hint)(ask.choices(choice)),
          div(cls := "set-width", title := hint, style := s"width: $pct%")(nbsp)
        )
      }
    })

  private def choiceContainer(ask: Ask): scalatags.Text.TypedTag[String] = {
    val sb = new mutable.StringBuilder("ask__choices")
    if (ask.isVertical) sb ++= " vertical"
    if (ask.isStretch) sb ++= " stretch"
    else if (ask.isCenter) sb ++= " center" // stretch overrides center
    div(cls := sb.toString)
  }

  def tooltip(ask: Ask, choice: Int)(implicit ctx: Context): String = {
    val sb         = new mutable.StringBuilder(256)
    val choiceText = ask.choices(choice)
    val hasPick    = getPick(ask).nonEmpty

    val count     = ask.count(choiceText)
    val isAuthor  = ctx.me.exists(_.id == ask.creator)
    val isShusher = ctx.me ?? Granter(Permission.Shusher)

    RenderType(ask) match {
      case BAR =>
        sb ++= pluralize("vote", count)
        if (ask.isPublic || isShusher)
          sb ++= s"\n\n${whoPicked(ask, choice)}"
      case QUIZ =>
        if (ask.isTally && hasPick || isAuthor || isShusher)
          sb ++= pluralize("pick", count)
        if ((hasPick || isAuthor) && ask.isPublic || isShusher)
          sb ++= s"\n\n${whoPicked(ask, choice)}"
      case POLL =>
        if (isAuthor || ask.isTally)
          sb ++= pluralize("vote", count)
        if (ask.isPublic && ask.isTally || isShusher)
          sb ++= s"\n\n${whoPicked(ask, choice)}"
      case _ =>
    }
    if (sb.isEmpty) choiceText else sb.toString
  }

  private def rankedTooltips(ask: Ask): IndexedSeq[String] = {
    val respondents = ask.picks ?? (picks => picks.size)
    val rankM       = ask.rankMatrix
    rankM foreach { m => m.mkString(", ") }
    val notables = List(
      0 -> "ranked this first",
      2 -> "chose this in their top three",
      4 -> "chose this in their top five"
    )
    ask.choices.zipWithIndex map { case (choiceText, choice) =>
      val sb = new mutable.StringBuilder(s"$choiceText:\n\n")
      notables filter (_._1 < rankM.length - 2) map { case (i, text) =>
        sb ++= s"  ${rankM(choice)(i)} $text\n"
      }
      sb.toString
    }
  }

  private def getPick(ask: Ask)(implicit ctx: Context): Option[Int] =
    ctx.me.flatMap(u => ask.picks.flatMap(_ get u.id) flatMap (_ headOption))

  private def getRanking(ask: Ask)(implicit ctx: Context): Option[Vector[Int]] =
    ctx.me.flatMap(u => ask.picks.flatMap(_ get u.id))

  private def hasPick(ask: Ask)(implicit ctx: Context): Boolean =
    ctx.me.exists(u => ask.hasPickFor(u.id))

  private def pluralize(item: String, n: Int): String =
    if (n == 0) s"No ${item}s" else if (n == 1) s"1 ${item}" else s"$n ${item}s"

  private def whoPicked(ask: Ask, choice: Int, max: Int = 40): String = {
    val who = ask.whoPicked(choice)
    who.take(max).mkString("", ", ", (who.length > max) ?? ", and others...")
  }

  private def validRanking(ask: Ask)(implicit ctx: Context): Vector[Int] = {
    val initialOrder = if (ask.isRandom) shuffle((0 until ask.choices.size).toVector) else (0 until ask.choices.size).toVector
    getRanking(ask).fold(initialOrder) { r =>
      if (r == Nil || r.distinct.sorted != initialOrder.sorted) {
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
