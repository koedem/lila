package lila.ask

import org.joda.time.DateTime

import lila.user.User

case class Ask(
    _id: Ask.ID,
    question: String,
    choices: Ask.Choices,
    tags: Ask.Tags,
    creator: User.ID,
    createdAt: DateTime,
    answer: Option[String],
    footer: Option[String], // reveal text for quizzes or optional text prompt for feedbacks
    picks: Option[Ask.Picks],
    feedback: Option[Ask.Feedback],
    url: Option[String]
) {

  def equivalent(a: Ask): Boolean =
    question == a.question &&
      choices == a.choices &&
      isPublic == a.isPublic &&
      isRanked == a.isRanked &&
      answer == a.answer &&
      footer == a.footer

  def participants: Seq[User.ID] =
    picks match {
      case Some(p) => p.keys.toSeq
      case None    => Nil
    }

  def isPublic: Boolean    = tags contains "public"
  def isTally: Boolean     = tags contains "tally"
  def isConcluded: Boolean = tags contains "concluded"
  def isVertical: Boolean  = tags exists (_ startsWith "vert")
  def isStretch: Boolean   = tags.exists(_ startsWith "stretch")
  def isFeedback: Boolean  = tags contains "feedback"

  def hasPickFor(uid: User.ID): Boolean = picks exists (_ contains uid)
  def firstPickFor(uid: User.ID): Option[Int] =
    picks flatMap (_ get uid flatMap (_ headOption))

  def rankingFor(uid: User.ID): Option[IndexedSeq[Int]] =
    picks flatMap (_ get uid)

  def hasFeedbackFor(uid: User.ID): Boolean     = feedback exists (_ contains uid)
  def feedbackFor(uid: User.ID): Option[String] = feedback flatMap (_ get uid)

  def count(choice: Int): Int    = picks.fold(0)(_.values.count(_.headOption contains choice))
  def count(choice: String): Int = count(choices indexOf choice)

  def whoPicked(choice: String): List[User.ID] = whoPicked(choices indexOf choice)
  def whoPicked(choice: Int): List[User.ID] =
    picks getOrElse(Nil) collect {
      case (uid, ls) if ls.headOption contains choice => uid
    } toList

  def whoPickedAt(choice: Int, rank: Int): List[User.ID] =
    picks getOrElse(Nil) collect {
      case (uid, ls) if ls.indexOf(choice) == rank => uid
    } toList

  @inline private def constrain(index: Int) =
    index atMost (choices.size-1) atLeast 0

  // index of returned vector maps to choices list, value is from [0f, choices.size-1f] where 0 is "best" rank
  def averageRank: Vector[Float] =
    picks match {
      case Some(pmap) if choices.nonEmpty && pmap.nonEmpty =>
        val results = Array.fill(choices.size)(0)
        pmap.values foreach { ranking =>
          for (it <- choices.indices)
            results(constrain(ranking(it))) += it
        }
        results.map(_ / pmap.size.toFloat).toVector.pp
      case _ =>
        Vector.fill(choices.size)(0f)
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
  type Picks    = Map[User.ID, Vector[Int]] // ranked list of indices into Choices
  type Feedback = Map[User.ID, String]

  def make(
      _id: Option[String],
      question: String,
      choices: Choices,
      tags: Tags,
      creator: User.ID,
      answer: Option[String],
      footer: Option[String]
  ) =
    Ask(
      _id = _id getOrElse (lila.common.ThreadLocalRandom nextString idSize),
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
