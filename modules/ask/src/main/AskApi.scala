package lila.ask

import scala.concurrent.Future
import reactivemongo.api.bson._
import lila.db.dsl._
import lila.hub.actors.Timeline
import lila.user.User
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

  def hasAskEmbed(text: String): Boolean = hasAskId(text)

  def reset(ask: Ask): Fu[Option[Ask]] = reset(ask._id)

  def reset(id: Ask.ID): Fu[Option[Ask]] =
    coll.ext.findAndUpdate[Ask]($id(id), $unset("picks"), fetchNewObject = true)

  def deleteAll(text: String): Funit =
    coll.delete.one($inIds(extractIds(text))).void

  def getAll(text: String): Fu[List[Ask]] = coll.byIds[Ask](extractIds(text))

  // terminology: "markdown" is the popular formatting syntax, "markup" is Ask definition language
  // freeze is what you call before storing user text in the database, it updates database objects
  // with ask markup and replaces them with non-char unicode sentinels and _ids
  def freeze(
      formText: String,
      creator: User,
      oldCookie: Option[Ask.Cookie] = None,
      isMarkdown: Boolean = false
  ): Fu[String] = {
    val sanitizer: String => String = if (isMarkdown) stripMarkdownEscapes else identity
    val askIntervals = markupIntervals(formText)
    askIntervals.map { case (start, end) =>
      upsert(
        textToAsk(formText.slice(start, end), creator, sanitizer)
      )
    }.sequenceFu map { asks =>
      val iter = asks.iterator
      val sb = new mutable.StringBuilder
      intervalClosure(askIntervals, formText.length) map { segment =>
        if (askIntervals contains segment) sb.append(s"$askIdSentinel{${iter.next()._id}}")
        else sb.append(formText.slice(segment._1, segment._2))
      }
      sb.toString
    }
  }

  // unfreeze replaces embedded ids in text with the markup to allow user edits
  def unfreeze(frozen: String): Fu[String] =
    if (!hasAskId(frozen)) fuccess(frozen)
    else getAll(frozen) map(unfreezeSync(frozen,_))

  def unfreezeSync(frozen: String, asks: Iterable[Ask]): String =
    if (asks.isEmpty) frozen
    else {
      val askIter = asks.iterator
      matchIdRe.replaceAllIn(frozen, askToText(askIter.next()))
    }

  def setUrl(frozen: String, url: String): Funit =
    if (!hasAskId(frozen)) funit
    else coll.update.one($inIds(extractIds(frozen)), $set("url" -> url), multi = true).void

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

  private def upsert(ask: Ask): Fu[Ask] =
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

  def stripAsks(text: String, n: Int = -1): String =
    matchIdRe.replaceAllIn(text, "").take(if (n == -1) text.length else n)

  // renders asks as html embedded in text
  def embed(text: String, asks: Map[String, String]): String = {
    matchIdRe.replaceAllIn(text, a => ~asks.get(a.group(1)))
  }

  // renders ask as markup text
  private def askToText(ask: Ask): String = {
    val sb = new mutable.StringBuilder
    sb ++= s"?? ${ask.question}\n"
    sb ++= s"?= askid:${ask._id}"
    sb ++= s"${ask.isPublic ?? " public"}"
    sb ++= s"${ask.isTally ?? " tally"}"
    sb ++= s"${ask.isConcluded ?? " concluded"}\n"
    sb ++= ask.choices.map(c => s"?${if (ask.answer.contains(c)) "@" else "#"} $c\n").mkString
    (sb ++= s"${ask.reveal.fold("")(a => s"?! $a\n")}").toString
  }

  // construct an Ask from the first markup in segment
  private def textToAsk(segment: String, creator: User, sanitize: String => String): Ask = {
    val params = extractParams(segment)
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
  }

  // these tuple types for value equality (same RegexMatch objects don't have that)
  type Interval = (Int, Int) // (start, end)
  type Intervals = Seq[(Int, Int)]

  // returns the (begin, end) offsets of ask markups in text.
  private def markupIntervals(text: String): Intervals =
    matchAskRe.findAllMatchIn(text).map(m => (m.start, m.end)).toSeq

  // assumes inputs are non-overlapping and sorted, returns subs and its complement in [0, upper)
  private def intervalClosure(subs: Intervals, upper: Int): Intervals = {
    val points = (0 :: subs.toList.flatten(i => List(i._1, i._2)) ::: upper :: Nil).distinct
    points.zip(points.tail)
  }

  // contains() is roughly 3x faster than simple regex matches() for me
  private def hasAskId(t: String): Boolean =
    t.contains(askIdSentinel) ?? matchIdRe.matches(t)

  private def extractId(text: String): Option[String] =
    matchIdRe.findFirstMatchIn(text).map(_ group 1)

  private def extractIds(text: String): Iterable[String] =
    matchIdRe.findAllMatchIn(text).map(_ group 1).toSeq

  private def extractQuestion(t: String): String = matchQuestionRe.findFirstMatchIn(t).get.group(1).trim

  private def extractParams(t: String): Option[String] =
    matchParamsRe.findFirstMatchIn(t).map(_.group(1).trim.toLowerCase)

  private def extractChoices(t: String): Ask.Choices =
    matchChoicesRe.findAllMatchIn(t).map(_.group(1).trim).distinct.toVector

  private def extractAnswer(t: String): Option[String] =
    matchAnswerRe.findFirstMatchIn(t).map(_.group(1).trim)

  private def extractReveal(t: String): Option[String] =
    matchRevealRe.findFirstMatchIn(t).map(_.group(1).trim)

  private def stripMarkdownEscapes(t: String): String = stripMarkdownRe.replaceAllIn(t, "$1")

  // markdown fuckery - strip the backslashes when markdown escapes one of the characters below

  private val stripMarkdownRe = raw"\\([*_`~.!{})(\[\]\-+|<>])".r // could be overkill

  // frozen
  private val askIdSentinel = "\ufdd6\ufdd4\ufdd2\ufdd0" // https://www.unicode.org/faq/private_use.html
  private val matchIdRe = s"$askIdSentinel\\{(\\S{8})}".r

  // markup
  private val matchAskRe = (
    raw"(?m)^\?\?\h*\S.*\R"                    // match "?? <question>"
      + raw"(?m)^(\?=(.*askid:(\S{8}))?.*\R)?" // match optional "?= <params>", id as group 3
      + raw"(\?[#@]\h*\S.*\R?){2,}"            // match 2 or more "?# <choice>"
      + raw"(\?!.*\R?)?"
    ).r
  private val matchQuestionRe = raw"(?m)^\?\?(.*)".r
  private val matchParamsRe   = raw"(?m)^\?=(.*)".r
  private val matchChoicesRe  = raw"(?m)^\?[#@](.*)".r
  private val matchAnswerRe   = raw"(?m)^\?@(.*)".r
  private val matchRevealRe   = raw"(?m)^\?!(.*)".r
}
