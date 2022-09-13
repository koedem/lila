package lila.ask

import org.joda.time.DateTime

import lila.user.User

case class Ask(
    _id: Ask.ID,
    question: String,
    choices: Ask.Choices,
    numChoices: Int, // redundant, for rank update validation without aggregation
    tags: Ask.Tags,
    creator: User.ID,
    createdAt: DateTime,
    answer: Option[String],
    reveal: Option[String],
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
      reveal == a.reveal

  def participants: Seq[User.ID] =
    picks match {
      case Some(p) => p.keys.toSeq
      case None    => Nil
    }

  def isPublic: Boolean = tags contains "public"
  def isTally: Boolean = tags contains "tally"
  def isConcluded: Boolean = tags contains "concluded"
  def isRanked: Boolean = tags exists(_ startsWith "rank")
  def isVertical: Boolean = tags exists(_ startsWith "vert")
  def isFeedback: Boolean = tags contains "feedback"

  def hasPick(uid: User.ID): Boolean = picks exists(_ contains uid)
  def getPick(uid: User.ID): Option[Int] =
    picks flatMap(_ get uid flatMap(_ headOption))

  def getRanking(uid: User.ID): Option[List[Int]] =
    picks flatMap(_ get uid)

  def hasFeedback(uid: User.ID): Boolean = feedback exists(_ contains uid)
  def getFeedback(uid: User.ID) : Option[String] = feedback flatMap(_ get uid)

  def count(choice: Int): Int    = picks.fold(0)(_.values.count(_.headOption contains choice))
  def count(choice: String): Int = count(choices indexOf choice)

  def whoPicked(choice: String): Seq[User.ID] = whoPicked(choices indexOf choice)
  def whoPicked(choice: Int): Seq[User.ID] =
    picks match {
      case Some(p) =>
        p.collect { case (text, i) if i.headOption contains choice => text }.toSeq
      case None => Nil
    }

  def isPoll: Boolean = answer isEmpty

  def isQuiz: Boolean = answer nonEmpty
}

object Ask {
  val idSize    = 8

  type ID       = String
  type Tags     = Set[String]
  type Choices  = IndexedSeq[String]
  type Picks    = Map[User.ID, List[Int]] // ranked list of indices into Choices
  type Feedback = Map[User.ID, String]

  def make(
      _id: Option[String],
      question: String,
      choices: Choices,
      tags: Tags,
      creator: User.ID,
      answer: Option[String],
      reveal: Option[String]
  ) =
    Ask(
      _id = _id getOrElse(lila.common.ThreadLocalRandom nextString idSize),
      question = question,
      choices = choices,
      numChoices = choices.size,
      tags = tags,
      createdAt = DateTime.now(),
      creator = creator,
      answer = answer,
      reveal = reveal,
      picks = None,
      feedback = None,
      url = None
    )
}
