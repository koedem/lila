package lila.ask

import scala.collection.mutable

import reactivemongo.api.bson._
import lila.db.dsl._
import lila.hub.actorApi.timeline.{ AskConcluded, Propagate }
import lila.hub.actors.Timeline
import lila.user.User
import lila.security.Granter

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

  def deleteAll(text: String): Funit = {
    if (hasAskId(text)) coll.delete.one($inIds(extractIds(text))).void
    else funit
  }

  def getAll(text: String): Fu[List[Ask]] =
    if (hasAskId(text)) coll.byIds[Ask](extractIds(text))
    else fuccess(Nil)

  /* terminology: "markdown" is the popular formatting syntax, "markup" is the ask definition language
   * "freeze" is method you call before storing user text in the database, it updates database objects
   * with ask markup and returns replacement text with substituted non-char-unicode sentinels and ids
   * "unfreeze" is the reverse process - getting markup from a frozen text
   */

  def freeze(
      text: String,
      creator: User,
      isMarkdown: Boolean = false
  ): Fu[Frozen] = {
    val sanitizer: String => String = if (isMarkdown) stripMarkdownEscapes else identity
    val askIntervals                = getMarkupIntervals(text)
    askIntervals.map { case (start, end) =>
      // rarely more than one of these in a text, otherwise this could be batched
      upsert(
        textToAsk(text.slice(start, end), creator, sanitizer)
      )
    }.sequenceFu map { asks =>
      val it = asks.iterator
      val sb = new mutable.StringBuilder(text.length)
      intervalClosure(askIntervals, text.length) map { seg =>
        if (it.hasNext && askIntervals.contains(seg)) sb ++= s"$frozenIdSentinel{${it.next()._id}}"
        else sb ++= text.slice(seg._1, seg._2)
      }
      Frozen(sb.toString, asks)
    }
  }

  // unfreeze replaces embedded ids in text with ask markup to allow user edits
  def unfreeze(frozen: Frozen): String =
    if (frozen.asks.isEmpty) frozen.text
    else {
      val iter = frozen.asks.iterator
      frozenIdRe.replaceAllIn(frozen.text, askToText(iter.next()))
    }

  // unfreezeAsync when you can spare the time and don't have the asks handy
  def unfreezeAsync(text: String): Fu[String] =
    if (!hasAskId(text)) fuccess(text)
    else getAll(text) map (asks => unfreeze(Frozen(text, asks)))

  // setUrl sets the link used in timeline event at poll conclusion
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
  // wraps freeze method return value and unfreeze parameter
  case class Frozen(text: String, asks: Iterable[Ask])

  // contains() is roughly 3x faster than simple regex matches() for me
  def hasAskId(t: String): Boolean = t.contains(frozenIdSentinel)

  // use to remove embedded sentinels for summaries and lite rendering purposes
  def stripAsks(text: String, n: Int = -1): String =
    frozenIdRe.replaceAllIn(text, "").take(if (n == -1) text.length else n)

  // used to render asks as html embedded in text
  def renderAsks(text: String, askFrags: Iterable[String]): String =
    if (askFrags.isEmpty) text
    else {
      val it = askFrags.iterator
      val sb = new mutable.StringBuilder(text.length + askFrags.foldLeft(0)((x, y) => x + y.length))
      // let none say i allocated too little or not enough
      val askIntervals = frozenIdRe.findAllMatchIn(text).map(m => (m.start, m.end)).toSeq
      intervalClosure(askIntervals, text.length) map { seg =>
        if (it.hasNext && askIntervals.contains(seg)) sb ++= it.next()
        else sb ++= text.slice(seg._1, seg._2)
      }
      sb.toString
    }

  // renders ask as markup text
  private def askToText(ask: Ask): String = {
    val sb = new mutable.StringBuilder(1024)
    sb ++= s"?? ${ask.question}\n"
    sb ++= s"?= id:${ask._id}"
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
      _id = extractIdParam(params),
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

  // these tuple types for value equality (functionally identical Regex.Match objects fail this)
  type Interval  = (Int, Int) // (start, end)
  type Intervals = Seq[(Int, Int)]

  // returns the (begin, end) offsets of ask markups in text.
  private def getMarkupIntervals(t: String): Intervals =
    askRe.findAllMatchIn(t).map(m => (m.start, m.end)).toSeq

  // assumes inputs are non-overlapping and sorted, returns subs and its complement in [0, upper)
  private def intervalClosure(subs: Intervals, upper: Int): Intervals = {
    val points = (0 :: subs.toList.flatten(i => List(i._1, i._2)) ::: upper :: Nil).distinct
    points.zip(points.tail)
  }

  private def extractIds(t: String): Iterable[String] =
    frozenIdRe.findAllMatchIn(t).map(_ group 1).toSeq

  private def extractQuestion(t: String): String = questionInAskRe.findFirstMatchIn(t).get.group(1).trim

  private def extractParams(t: String): Option[String] =
    paramsInAskRe.findFirstMatchIn(t).map(_.group(1).trim.toLowerCase)

  private def extractIdParam(tOpt: Option[String]): Option[String] =
    tOpt.flatMap(idInParamsRe.findFirstMatchIn(_).map(_.group(1)))

  private def extractChoices(t: String): Ask.Choices =
    choicesInAskRe.findAllMatchIn(t).map(_.group(1).trim).distinct.toVector

  private def extractAnswer(t: String): Option[String] =
    answerInAskRe.findFirstMatchIn(t).map(_.group(1).trim)

  private def extractReveal(t: String): Option[String] =
    revealInAskRe.findFirstMatchIn(t).map(_.group(1).trim)

  private def stripMarkdownEscapes(t: String): String = stripMarkdownRe.replaceAllIn(t, "$1")

  // markdown fuckery - strip the backslashes when markdown escapes one of the characters below

  private val stripMarkdownRe = raw"\\([*_`~.!{})(\[\]\-+|<>])".r // could be overkill

  // frozen
  private val frozenIdSentinel = "\ufdd6\ufdd4\ufdd2\ufdd0" // https://www.unicode.org/faq/private_use.html
  private val frozenIdRe       = s"$frozenIdSentinel\\{(\\S{8})}".r

  // markup
  private val askRe = (
    raw"(?m)^\?\?\h*\S.*\R"         // match "?? <question>"
      + raw"(\?=.*\R)?"             // match optional "?= <params>"
      + raw"(\?[#@]\h*\S.*\R?){2,}" // match 2 or more "?# <choice>" or "?@ <choice>"
      + raw"(\?!.*\R?)?"            // match option "?! <reveal>"
  ).r
  private val questionInAskRe = raw"^\?\?(.*)".r
  private val paramsInAskRe   = raw"(?m)^\?=(.*)".r
  private val idInParamsRe    = raw"id:(\S{8})".r
  private val choicesInAskRe  = raw"(?m)^\?[#@](.*)".r
  private val answerInAskRe   = raw"(?m)^\?@(.*)".r
  private val revealInAskRe   = raw"(?m)^\?!(.*)".r
}
