package lila.ask

import scala.collection.mutable

import reactivemongo.api.bson._
import lila.db.dsl._
import lila.hub.actorApi.timeline.{ AskConcluded, Propagate }
import lila.user.User

import lila.security.Granter

final class AskApi(
    coll: Coll,
    timeline: lila.hub.actors.Timeline
)(implicit ec: scala.concurrent.ExecutionContext) {

  import AskApi._

  implicit val AskBSONHandler: BSONDocumentHandler[Ask] = Macros.handler[Ask]

  def get(id: Ask.ID): Fu[Option[Ask]] = coll.byId[Ask](id)

  def pick(id: Ask.ID, uid: User.ID, pick: Option[Int]): Fu[Option[Ask]] =
    coll.ext.findAndUpdate[Ask](
      selector = $and($id(id), $doc("isConcluded" -> false)),
      update = { pick.fold($unset(s"picks.$uid"))(p => $set(s"picks.$uid" -> List(p))) },
      fetchNewObject = true
    ) flatMap {
      case None => get(id) // concluded prior to the pick? look up the ask.
      case ask  => fuccess(ask)
    }

  def rank(id: Ask.ID, uid: User.ID, ranking: List[Int]): Funit = {
    if (ranking != Nil && (ranking.distinct.size != ranking.size || ranking.min < 1 || ranking.max > ranking.size))
      return funit // be safe

    val (selector, updater) =
      if (ranking == Nil) (
        $and($id(id), $doc("isConcluded" -> false)),
        $unset(s"picks.$uid")
      ) else (
        $and(
          $id(id),
          $doc("isConcluded" -> false),
          $doc("numChoices" -> $eq(ranking.length)) // be safe
        ),
        $set(s"picks.$uid" -> ranking)
      )
    coll.ext.findAndUpdate[Ask](selector = selector, update = updater).void
  }

  def conclude(ask: Ask): Fu[Option[Ask]] = conclude(ask._id)

  def conclude(id: Ask.ID): Fu[Option[Ask]] =
    coll.ext.findAndUpdate[Ask]($id(id), $set("isConcluded" -> true), fetchNewObject = true) collect {
      case Some(ask) =>
        timeline ! Propagate(AskConcluded(ask.creator, ask.question, ~ask.url))
          .toUsers(ask.participants.toList)
          .exceptUser(ask.creator)
        ask.some
    }

  def reset(ask: Ask): Fu[Option[Ask]] = reset(ask._id)

  def reset(id: Ask.ID): Fu[Option[Ask]] =
    coll.ext.findAndUpdate[Ask]($id(id), $unset("picks"), fetchNewObject = true)

  def deleteAll(text: String): Funit = {
    if (hasAskId(text)) coll.delete.one($inIds(extractIds(text))).void
    else funit
  }

  def getAll(text: String): Fu[List[Option[Ask]]] =
    if (!hasAskId(text)) fuccess(Nil)
    else {
      val orderedIds = extractIds(text)
      coll.byIds[Ask](orderedIds) map { ids =>
        // apparently insertion order means nothing
        orderedIds map { id => ids.find(_._id == id)}
      }
    }

  /* terminology: "markdown" is the popular formatting syntax, "markup" is the ask definition language.
   * "freeze" transforms form text prior to database storage and updates collection objects with any
   * ask markup.  it returns "frozen" replacement text with magic/id pairs substituted for the markup.
   * "unfreeze" prepares for editing - substituting ask markup back into a previously frozen text
   */

  /*def freeze(text: String, creator: User): Fu[Frozen] = {
    val askIntervals                = getMarkupIntervals(text)
    askIntervals.map { case (start, end) =>
      // rarely more than a few of these in a text, otherwise this should be batched
      upsert(
        textToAsk(text.substring(start, end), creator)
      )
    }.sequenceFu map { asks =>
      val it = asks.iterator
      val sb = new java.lang.StringBuilder(text.length)
      intervalClosure(askIntervals, text.length) map { seg =>
        if (it.hasNext && askIntervals.contains(seg)) sb.append(s"$frozenIdMagic{${it.next()._id}}")
        else sb.append(text, seg._1, seg._2)
      }
      Frozen(sb.toString, asks)
    }
  }*/
  def freeze(text: String, creator: User): Fu[Frozen] = {
    val askIntervals                = getMarkupIntervals(text)
    askIntervals.map { case (start, end) =>
      textToAsk(text.substring(start, end), creator))
    } map { asks =>
      val it = asks.iterator
      val sb = new java.lang.StringBuilder(text.length)
      intervalClosure(askIntervals, text.length) map { seg =>
        if (it.hasNext && askIntervals.contains(seg)) sb.append(s"$frozenIdMagic{${it.next()._id}}")
        else sb.append(text, seg._1, seg._2)
      }
      Frozen(sb.toString, asks)
    }
  }


  // commit flushes the asks to the db and optionally sets the link used in timeline event at poll conclusion
  def setUrl(frozen: String, url: Option[String]): Funit =
    if (!hasAskId(frozen)) funit
    else coll.update.one($inIds(extractIds(frozen)), $set("url" -> url), multi = true).void


  // unfreeze replaces embedded ids in text with ask markup to allow user edits
  def unfreeze(text: String, asks: Iterable[Option[Ask]]): String =
    if (asks.isEmpty) text
    else {
      val iter = asks.iterator
      frozenIdRe.replaceAllIn(text, iter.next().fold(askDeletedFrag)(askToText))
    }

  // unfreezeAsync when you can spare the time and don't have the asks handy
  def unfreezeAsync(text: String): Fu[String] =
    if (!hasAskId(text)) fuccess(text)
    else getAll(text) map (asks => unfreeze(text, asks))

  // these next three are here for convenience and just redirect to object methods
  def hasAskId(text: String): Boolean = AskApi.hasAskId(text)

  def stripAsks(text: String, n: Int = -1): String = AskApi.stripAsks(text, n)

  def renderAsks(text: String, askFrags: Iterable[String]): String =
    AskApi.renderAsks(text, askFrags)

  // only preserve votes if important fields haven't been altered
  private def upsert(ask: Ask): Fu[Ask] =
    coll.byId[Ask](ask._id) flatMap {
      case Some(dbAsk) =>
        if (dbAsk.equivalent(ask)) fuccess(dbAsk)
        else {
          val askWithUrl = ask.copy(url = dbAsk.url) // no need to call setUrl again on edits
          coll.update.one($id(ask._id), askWithUrl) inject askWithUrl
        }
      case None =>
        coll.insert.one(ask) inject ask
    }

  private def delete(id: Ask.ID): Funit = coll.delete.one($id(id)).void
}

object AskApi {
  // wraps freeze method's return value and unfreeze's parameter
  case class Frozen(text: String, asks: Iterable[Ask])

  // contains() is much faster than regex matches so believe in magic
  def hasAskId(t: String): Boolean = t.contains(frozenIdMagic)

  // use to remove frozen artifacts for summaries
  def stripAsks(text: String, n: Int = -1): String =
    frozenIdRe.replaceAllIn(text, "").take(if (n == -1) text.length else n)

  // used to combine ask html fragments with frozen text
  def renderAsks(text: String, askFrags: Iterable[String]): String = {
    val sb = new java.lang.StringBuilder(text.length + askFrags.foldLeft(0)((x, y) => x + y.length))
    val it = askFrags.iterator
    val magicIntervals = frozenIdRe.findAllMatchIn(text).map(m => (m.start, m.end)).toList
    intervalClosure(magicIntervals, text.length) map { seg =>
      if (it.hasNext && magicIntervals.contains(seg)) sb.append(it.next())
      else sb.append(text, seg._1, seg._2)
    }
    sb.toString
  }

  val askDeletedFrag = "&lt;poll deleted&gt;<br>"

  // renders ask as markup text
  private def askToText(ask: Ask): String = {
    val sb = new mutable.StringBuilder(1024)
    // scala StringBuilder here for ++= readability
    sb ++= s"?? ${ask.question}\n"
    sb ++= s"?= askId:${ask._id}"
    sb ++= s"${ask.isPublic ?? " public"}"
    sb ++= s"${ask.isTally ?? " tally"}"
    sb ++= s"${ask.isRanked ?? " rank"}"
    sb ++= s"${ask.isConcluded ?? " concluded"}\n"
    sb ++= ask.choices.map(c => s"?${if (ask.answer.contains(c)) "@" else "#"} $c\n").mkString
    sb ++= s"${ask.reveal.fold("")(r => s"?! $r\n")}"
    sb.toString
  }

  // construct an Ask from the first markup in segment
  private def textToAsk(segment: String, creator: User): Ask = {
    val params = extractParams(segment)
    val lowerParams = params ?? (_ toLowerCase)
    Ask.make(
      _id = extractIdParam(params),
      question = extractQuestion(segment),
      choices = extractChoices(segment),
      isPublic = lowerParams contains "public",
      isTally = lowerParams contains "tally",
      isConcluded = lowerParams contains "concluded",
      isRanked = lowerParams contains "rank",
      creator = creator.id,
      answer = extractAnswer(segment),
      reveal = extractReveal(segment)
    )
  }

  // rely on intervals rather than Regex.Match for value equality
  type Interval  = (Int, Int) // [start, end)
  type Intervals = List[(Int, Int)]

  // returns the (begin, end) offsets of ask markups in text.
  private def getMarkupIntervals(t: String): Intervals =
    askRe.findAllMatchIn(t).map(m => (m.start, m.end)) toList

  // assumes inputs are non-overlapping and sorted, returns subs and its complement in [0, upper)
  private def intervalClosure(subs: Intervals, upper: Int): Intervals = {
    val points = (0 :: subs.flatten(i => List(i._1, i._2)) ::: upper :: Nil).distinct
    points.zip(points.tail)
  }

  private def extractIds(t: String): List[String] = { // used so much it deserves some optimization
    var idIndex = t.indexOf(frozenIdMagic)
    frozenIdRe.findAllMatchIn(t).map(_ group 1) toList
  }

  private def extractQuestion(t: String): String =
    questionInAskRe.findFirstMatchIn(t).get.group(1).trim // NPE good here

  private def extractParams(t: String): Option[String] =
    paramsInAskRe.findFirstMatchIn(t).map(_ group 1)

  private def extractIdParam(tOpt: Option[String]): Option[String] =
    tOpt.flatMap(idInParamsRe.findFirstMatchIn(_).map(_ group 1))

  private def extractChoices(t: String): Ask.Choices =
    choicesInAskRe.findAllMatchIn(t).map(_.group(1).trim).distinct toVector

  private def extractAnswer(t: String): Option[String] =
    answerInAskRe.findFirstMatchIn(t).map(_.group(1).trim)

  private def extractReveal(t: String): Option[String] =
    revealInAskRe.findFirstMatchIn(t).map(_.group(1).trim)

  // frozen
  private val frozenIdMagic = "\ufdd6\ufdd4\ufdd2\ufdd0" // https://www.unicode.org/faq/private_use.html
  private val frozenIdRe    = s"$frozenIdMagic\\{(\\S{8})}".r

  // markup
  private val askRe = (
    raw"(?m)^\?\?\h*\S.*\R"         // match "?? <question>"
      + raw"(\?=.*\R)?"             // match optional "?= <params>"
      + raw"(\?[#@]\h*\S.*\R?){2,}" // match 2 or more "?# <choice>" or "?@ <choice>"
      + raw"(\?!.*\R?)?"            // match option "?! <reveal>"
  ).r
  private val questionInAskRe = raw"^\?\?(.*)".r
  private val paramsInAskRe   = raw"(?m)^\?=(.*)".r
  private val idInParamsRe    = raw"askId:(\S{8})".r
  private val choicesInAskRe  = raw"(?m)^\?[#@](.*)".r
  private val answerInAskRe   = raw"(?m)^\?@(.*)".r
  private val revealInAskRe   = raw"(?m)^\?!(.*)".r
}
