package lila.ask

import scala.collection.mutable

import reactivemongo.api.bson._
import lila.db.dsl._
import lila.hub.actorApi.timeline.{ AskConcluded, Propagate }
import lila.user.User

import lila.security.Granter

/* an ASK is an object that represents a single poll or quiz question.  MARKUP means the ask
 * definition language.  the FREEZE process transforms form text prior to database storage and
 * updates collection objects with ask markup.  freeze methods return FROZEN replacement text
 * with magic/id tags substituted for markup. UNFREEZE methods allow editing by substituting the
 * markup back into a previously frozen text.   everything else should be pretty self explanatory
 */

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
      return funit // It's the RUSSIANS!

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

  def asksIn(text: String): Fu[List[Option[Ask]]] =
    if (!hasAskId(text)) fuccess(Nil)
    else {
      val orderedIds = extractIds(text)
      coll.byIds[Ask](orderedIds) map { ids =>
        // apparently insertion order means nothing
        orderedIds map { id => ids.find(_._id == id)}
      }
    }

  // freeze is synchronous but requires a subsequent async "commit" step that actually stores the asks
  def freeze(text: String, creator: User): Frozen = {
    val askIntervals                = getMarkupIntervals(text)
    val asks = askIntervals.map { case (start, end) =>
      textToAsk(text.substring(start, end), creator)
    }
    val it = asks.iterator
    val sb = new java.lang.StringBuilder(text.length)
    intervalClosure(askIntervals, text.length) map { seg =>
      if (it.hasNext && askIntervals.contains(seg)) sb.append(s"$frozenIdMagic{${it.next()._id}}")
      else sb.append(text, seg._1, seg._2)
    }
    Frozen(sb.toString, asks)
  }

  // commit flushes the asks to the db and optionally sets the timeline entry link at poll conclusion
  def commit(frozen: Frozen, url: Option[String] = None): Fu[Iterable[Ask]] = {
    frozen.asks map { ask =>
      upsert(ask.copy(url = url))
    } sequenceFu
  }

  // freezeAsync is freeze & commit together without the url.  call setUrl once you know it
  def freezeAsync(text: String, creator: User): Fu[Frozen] = {
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
  }

  // we should probably call this at some point after freezeAsync in the edit/update flow, leave it for now
  def setUrl(frozen: String, url: Option[String]): Funit =
    if (!hasAskId(frozen)) funit
    else coll.update.one($inIds(extractIds(frozen)), $set("url" -> url), multi = true).void

  // unfreeze replaces embedded ids in text with ask markup to allow user edits
  def unfreeze(text: String, asks: Iterable[Option[Ask]]): String =
    if (asks.isEmpty) text
    else {
      val it = asks.iterator
      frozenIdRe.replaceAllIn(text, _ => it.next().fold(askDeletedFrag)(askToText))
    }

  // unfreezeAsync when you can spare the time and don't have the asks handy
  def unfreezeAsync(text: String): Fu[String] =
    if (!hasAskId(text)) fuccess(text)
    else asksIn(text) map (asks => unfreeze(text, asks))

  // these just redirect to the object for convenience
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
          val askWithUrl = ask.copy(url = dbAsk.url)
          // TODO - should probably call setUrl from ublog & post after updates in case a new ask was added
          coll.update.one($id(ask._id), askWithUrl) inject askWithUrl
        }
      case None =>
        coll.insert.one(ask) inject ask
    }

  private def delete(id: Ask.ID): Funit = coll.delete.one($id(id)).void
}

object AskApi {
  // used as return value / parameter
  case class Frozen(text: String, asks: Iterable[Ask])

  // believe in magic
  def hasAskId(text: String): Boolean = text.contains(frozenIdMagic)

  // remove frozen artifacts for summaries
  def stripAsks(text: String, n: Int = -1): String =
    frozenIdRe.replaceAllIn(text, "").take(if (n == -1) text.length else n)

  // combine ask html fragments with frozen text
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

  // when we can't find the thing
  val askDeletedFrag = "&lt;poll deleted&gt;<br>"

  // render ask as markup text
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

  // use index pairs rather than Regex.Match for value equality
  type Interval  = (Int, Int) // [start, end)
  type Intervals = List[(Int, Int)]

  // return list of (start, end) indices of ask markups in text.
  private def getMarkupIntervals(t: String): Intervals =
    askRe.findAllMatchIn(t).map(m => (m.start, m.end)) toList

  // return subs and its complement in [0, upper), assumes inputs are non-overlapping and sorted
  private def intervalClosure(subs: Intervals, upper: Int): Intervals = {
    val points = (0 :: subs.flatten(i => List(i._1, i._2)) ::: upper :: Nil).distinct
    points.zip(points.tail)
  }

  // extractIds is called often - don't use regex for simple processing that should be fast
  // magic/id in a frozen text looks like:  ﷖﷔﷒﷐{8_charId}
  private def extractIds(t: String): List[String] = {
    var idIndex = t.indexOf(frozenIdMagic)
    val ids = mutable.ListBuffer[String]()
    while (idIndex != -1 && idIndex < t.length - 13) {    // 14 is total magic length
      ids addOne t.substring(idIndex + 5, idIndex + 13)   // (5, 13) delimit id within magic
      idIndex = t.indexOf(frozenIdMagic, idIndex + 14)
    }
    ids.toList
  }

  // frozen id magic https://www.unicode.org/faq/private_use.html
  private val frozenIdMagic = "\ufdd6\ufdd4\ufdd2\ufdd0"
  private val frozenIdRe    = s"$frozenIdMagic\\{(\\S{8})}".r

  private def extractQuestion(t: String): String =
    questionInAskRe.findFirstMatchIn(t).get.group(1).trim // NPE is desired here

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
