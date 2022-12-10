package lila.ask

import org.joda.time.DateTime
import ornicar.scalalib.ThreadLocalRandom

import lila.user.User

case class Ask(
    _id: Ask.ID,
    question: String,
    choices: Ask.Choices,
    tags: Ask.Tags,
    creator: UserId,
    createdAt: DateTime,
    answer: Option[String],
    footer: Option[String], // reveal text for quizzes or optional text prompt for feedbacks
    picks: Option[Ask.Picks],
    feedback: Option[Ask.Feedback],
    url: Option[String]
) {

  // changes to any of the fields checked in compatible will invalidate votes and feedback
  def compatible(a: Ask): Boolean =
    question == a.question &&
      choices == a.choices &&
      answer == a.answer &&
      footer == a.footer &&
      creator == a.creator &&
      isPublic == a.isPublic &&
      isRanked == a.isRanked

  def merge(dbAsk: Ask): Ask =
    if (this.compatible(dbAsk)) // keep votes & feedback
      if (tags equals dbAsk.tags) dbAsk
      else dbAsk.copy(tags = tags)
    else copy(url = dbAsk.url) // discard votes & feedback

  def participants: Seq[UserId] =
    picks match {
      case Some(p) => p.keys.toSeq
      case None    => Nil
    }

  def isPublic: Boolean    = tags contains "public"
  def isTally: Boolean     = tags contains "tally"
  def isConcluded: Boolean = tags contains "concluded"
  def isCenter: Boolean    = tags contains "center"
  def isRandom: Boolean    = tags contains "random"
  def isVertical: Boolean  = tags exists (_ startsWith "vert")
  def isStretch: Boolean   = tags.exists(_ startsWith "stretch")
  def isFeedback: Boolean  = tags contains "feedback"

  def hasPickFor(uid: UserId): Boolean = picks exists (_ contains uid)
  def firstPickFor(uid: UserId): Option[Int] =
    picks flatMap (_ get uid flatMap (_ headOption))

  def rankingFor(uid: UserId): Option[IndexedSeq[Int]] =
    picks flatMap (_ get uid)

  def hasFeedbackFor(uid: UserId): Boolean     = feedback exists (_ contains uid)
  def feedbackFor(uid: UserId): Option[String] = feedback flatMap (_ get uid)

  def count(choice: Int): Int    = picks.fold(0)(_.values.count(_.headOption contains choice))
  def count(choice: String): Int = count(choices indexOf choice)

  def whoPicked(choice: String): List[UserId] = whoPicked(choices indexOf choice)
  def whoPicked(choice: Int): List[UserId] =
    picks getOrElse (Nil) collect {
      case (uid, ls) if ls.headOption contains choice => uid
    } toList

  def whoPickedAt(choice: Int, rank: Int): List[UserId] =
    picks getOrElse (Nil) collect {
      case (uid, ls) if ls.indexOf(choice) == rank => uid
    } toList

  @inline private def constrain(index: Int) =
    index atMost (choices.size - 1) atLeast 0

  // index of returned vector maps to choices list, value is from [0f, choices.size-1f] where 0 is "best" rank
  def averageRank: Vector[Float] =
    picks match {
      case Some(pmap) if choices.nonEmpty && pmap.nonEmpty =>
        val results = Array.ofDim[Int](choices.size)
        pmap.values foreach { ranking =>
          for (it <- choices.indices)
            results(constrain(ranking(it))) += it
        }
        results.map(_ / pmap.size.toFloat).toVector.pp
      case _ =>
        Vector.fill(choices.size)(0f)
    }
  // a square matrix M describing the response rankings where each element M[i][j] is the number of
  // respondents who preferred the choice i at rank j or below (effectively in the top j+1 picks)
  def rankMatrix: Array[Array[Int]] =
    picks match {
      case Some(pmap) if choices.nonEmpty && pmap.nonEmpty =>
        val n   = choices.size
        val mat = Array.ofDim[Int](n, n)
        pmap.values foreach { ranking =>
          for (i <- choices.indices)
            for (j <- choices.indices)
              mat(i)(j) += (if (ranking(i) <= j) 1 else 0)
        }
        mat
      case _ =>
        Array.ofDim[Int](0, 0)
    }

  def isPoll: Boolean   = answer isEmpty
  def isRanked: Boolean = tags exists (_ startsWith "rank")
  def isQuiz: Boolean   = answer nonEmpty
}

object Ask {
  val idSize = 8

  type ID       = String
  type Tags     = Set[String]
  type Choices  = Vector[String]
  type Picks    = Map[UserId, Vector[Int]] // ranked list of indices into Choices
  type Feedback = Map[UserId, String]

  def make(
      _id: Option[String],
      question: String,
      choices: Choices,
      tags: Tags,
      creator: UserId,
      answer: Option[String],
      footer: Option[String]
  ) =
    Ask(
      _id = _id getOrElse (ThreadLocalRandom nextString idSize),
      question = question,
      choices = choices,
      tags = tags,
      createdAt = DateTime.now(),
      creator = creator,
      answer = answer,
      footer = footer,
      picks = None,
      feedback = None,
      url = None
    )
}
