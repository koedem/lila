package lila.ask

import scala.concurrent.Future
import reactivemongo.api.bson._
import lila.db.dsl._
import lila.hub.actors.Timeline
import lila.user.User
import lila.ask.Ask.imports._
import lila.security.Granter
import lila.hub.actorApi.timeline.{ AskConcluded, Propagate }

import scala.collection.mutable

final class AskApi(
    coll: Coll,
    timeline: Timeline
)(implicit ec: scala.concurrent.ExecutionContext) {

  import AskApi._

  implicit val AskBSONHandler: BSONDocumentHandler[Ask] = Macros.handler[Ask]

  def get(id: Ask.ID): Fu[Option[Ask]] = coll.byId[Ask](id)

  def pick(id: Ask.ID, uid: User.ID, pick: Option[Int]): Fu[Option[Ask]] =
    coll.ext.findAndUpdate[Ask](
      selector = $and($id(id), $doc("isConcluded" -> false)),
      update = { pick.fold($unset(s"picks.$uid"))(p => $set(s"picks.$uid" -> p)) },
      fetchNewObject = true
    ) flatMap {
      case None => get(id) // was concluded prior to the pick? look up the ask.
      case ask  => fuccess(ask)
    }

  def conclude(ask: Ask): Fu[Option[Ask]] = conclude(ask._id)

  def conclude(id: Ask.ID): Fu[Option[Ask]] =
    coll.ext.findAndUpdate[Ask]($id(id), $set("isConcluded" -> true), fetchNewObject = true).map {
      case Some(ask) =>
        timeline ! Propagate(AskConcluded(ask.creator, ask.question, ~ask.url))
          .toUsers(ask.participants.toList)
          .exceptUser(ask.creator)
        ask.some
      case None => None
    }

  def reset(ask: Ask): Fu[Option[Ask]] = reset(ask._id)

  def reset(id: Ask.ID): Fu[Option[Ask]] =
    coll.ext.findAndUpdate[Ask]($id(id), $unset("picks"), fetchNewObject = true)

  def deleteAsks(cookie: Option[Ask.Cookie]): Funit =
    coll.delete.one($inIds(extractCookieIds(cookie))).void

  def prepare(
      formText: String,
      creator: User,
      oldCookie: Option[Ask.Cookie] = None,
      isMarkdown: Boolean = false
  ): Fu[Updated] = {
    if (true && !Granter(_.Pollster)(creator)) {

      val markupIntervals            = getMarkupOffsets(formText)
      val sanitize: String => String = if (isMarkdown) stripMarkdownEscapes else identity

      val asks = getIntervalClosure(markupIntervals, formText.length) map { interval =>
        val segment = formText.slice(interval._1, interval._2)

        if (markupIntervals contains interval) {
          val params = extractParams(segment)
          Right(
            Ask.make(
              _id = extractId(segment),
              question = sanitize(extractQuestion(segment)),
              choices = extractChoices(segment) map sanitize,
              isPublic = params.fold(false)(_ contains "public"),
              isTally = params.fold(false)(_ contains "tally"),
              isConcluded = params.fold(false)(_ contains "concluded"),
              creator = creator.id,
              answer = extractAnswer(segment) map sanitize,
              reveal = extractReveal(segment) map sanitize
            )
          )
        } else Left(segment)
      }
      Future.sequence(asks.collect { case Right(a) => update(a) }) inject {
        val ids = asks.collect { case Right(a) => a._id }
        extractCookieIds(oldCookie).filterNot(ids contains) map delete
        Updated(asks.map(_.fold(identity, a => askToText(a))).mkString, makeV1Cookie(ids))
      }
    } else fuccess(Updated(formText, None))
  }

  def render(
      text: String,
      url: Option[String],
      formatter: Option[String => String] = None
  ): Fu[Seq[Ask.RenderElement]] = {
    val markupOffsets = getMarkupOffsets(text)
    Future.sequence(getIntervalClosure(markupOffsets, text.length) map { interval =>
      val txt = text.slice(interval._1, interval._2)

      if (!markupOffsets.contains(interval) || !hasId(txt))
        fuccess(isText(formatter.fold(txt)(_(txt))))
      else {
        get(extractId(txt).get) flatMap { // .get ok due to hasId check in conditional
          case None =>
            fuccess(isText("[deleted]"))
          case Some(ask) =>
            url.map(setUrl(ask, _))
            fuccess(isAsk(ask))
        }
      }
    })
  }

  def setUrl(cookie: Option[Ask.Cookie], url: String): Funit =
    coll.update.one($inIds(extractCookieIds(cookie)), $set("url" -> url), multi = true).void

  /*
  def getUserSummary(cookie: Option[Ask.Cookie], uid: User.ID): Fu[Seq[String]] =
    coll
      .find($inIds(extractCookieIds(cookie)))
      .cursor[Ask]()
      .list()
      .map(askList =>
        askList map { ask =>
          ask.getPick(uid).fold("") { i =>
            val choice = ask.choices(i)
            s"${ask.question}:  $choice" +
              ask.answer.fold("")(a => (a != choice) ?? s" ($a)")
          }
        }
      )
   */

  private def update(ask: Ask): Fu[Ask] =
    coll.byId[Ask](ask._id) map {
      case Some(dbAsk) =>
        if (dbAsk.invalidatedBy(ask)) {
          coll.update.one($id(ask._id), ask).void
          ask
        } else dbAsk
      case None =>
        insert(ask)
        ask
    }

  private def insert(ask: Ask): Funit = coll.insert.one(ask).void

  private def delete(id: Ask.ID): Funit = coll.delete.one($id(id)).void

  private def setUrl(ask: Ask, url: String): Funit =
    if (ask.url.contains(url)) funit
    else
      coll.ext
        .findAndUpdate[Ask](
          selector = $id(ask._id),
          update = $set("url" -> url),
          fetchNewObject = false
        )
        .void
}

object AskApi {

  case class Updated(text: String, cookie: Option[Ask.Cookie])

  def stripAsks(text: String, n: Int = -1): String =
    matchAskRe.replaceAllIn(text, "").take(if (n == -1) text.length else n)

  // markdown fuckery - strip the backslashes when markdown escapes one of the characters below
  private val stripMarkdownRe = raw"\\([*_`~.!{})(\[\]\-+|<>])".r // that could be overkill

  // markup
  private val matchAskRe = (
    raw"(?m)^\?\?\h*\S.*\R"                    // match "?? <question>"
      + raw"(?m)^(\?=(.*askid:(\S{8}))?.*\R)?" // match optional "?= <params>", id as group 3
      + raw"(\?[#@]\h*\S.*\R?){2,}"            // match 2 or more "?# <choice>"
      + raw"(\?!.*\R?)?"                       // match optional "?! <reveal>"
  ).r
  private val matchIdRe       = raw"(?m)^\?=.*askid:(\S{8})".r
  private val matchQuestionRe = raw"(?m)^\?\?(.*)".r
  private val matchParamsRe   = raw"(?m)^\?=(.*)".r
  private val matchChoicesRe  = raw"(?m)^\?[#@](.*)".r
  private val matchAnswerRe   = raw"(?m)^\?@(.*)".r
  private val matchRevealRe   = raw"(?m)^\?!(.*)".r

  // cookie
  private val matchCookieIdsRe = raw"v1:\[([^]]+)]".r

  // renders ask as markup text
  private def askToText(ask: Ask): String = {
    val sb = new mutable.StringBuilder("")
    sb ++= s"?? ${ask.question}\n"
    sb ++= s"?= askid:${ask._id}"
    sb ++= s"${ask.isPublic ?? " public"}"
    sb ++= s"${ask.isTally ?? " tally"}"
    sb ++= s"${ask.isConcluded ?? " concluded"}\n"
    sb ++= ask.choices.map(c => s"?${if (ask.answer.contains(c)) "@" else "#"} $c\n").mkString
    (sb ++= s"${ask.reveal.fold("")(a => s"?! $a\n")}").toString
  }

  type Intervals = Seq[(Int, Int)]

  // assumes inputs are non-overlapping and sorted, returns subs and its complement in [0, upper)
  private def getIntervalClosure(subs: Intervals, upper: Int): Intervals = {
    val points = (0 :: subs.toList.flatten(i => List(i._1, i._2)) ::: upper :: Nil).distinct
    points.zip(points.tail)
  }

  // returns the (begin, end) offsets of ask markups in text.
  private def getMarkupOffsets(text: String): Seq[(Int, Int)] =
    matchAskRe.findAllMatchIn(text).map(m => (m.start, m.end)).toList

  private def makeV1Cookie(idList: Seq[Ask.ID]): Option[String] =
    idList match {
      case Nil => None
      case ids => ids.mkString(s"v1:[", ",", "]").some
    }

  private def extractCookieIds(cookie: Option[Ask.Cookie]): Seq[Ask.ID] =
    matchCookieIdsRe.findFirstMatchIn(~cookie).map(_.group(1)) match {
      case Some(m) => ",".r.split(m).toSeq
      case None    => Nil
    }

  private def extractQuestion(t: String): String = matchQuestionRe.findFirstMatchIn(t).get.group(1).trim

  private def extractParams(t: String): Option[String] =
    matchParamsRe.findFirstMatchIn(t).map(_.group(1).trim.toLowerCase)

  private def extractChoices(t: String): Ask.Choices =
    matchChoicesRe.findAllMatchIn(t).map(_.group(1).trim).distinct.toVector

  private def hasId(t: String): Boolean = extractId(t).nonEmpty

  private def extractId(t: String): Option[Ask.ID] = matchIdRe.findFirstMatchIn(t).map(_.group(1))

  private def extractAnswer(t: String): Option[String] =
    matchAnswerRe.findFirstMatchIn(t).map(_.group(1).trim)

  private def extractReveal(t: String): Option[String] =
    matchRevealRe.findFirstMatchIn(t).map(_.group(1).trim)

  private def stripMarkdownEscapes(t: String): String = stripMarkdownRe.replaceAllIn(t, "$1")
}
