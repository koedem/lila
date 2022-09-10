package lila.ask

import org.joda.time.DateTime

import lila.user.User

case class Ask(
    _id: Ask.ID,
    question: String,
    choices: Ask.Choices,
    isPublic: Boolean,    // users picks are not secret
    isTally: Boolean,     // results visible before closing
    isConcluded: Boolean, // no more picks
    isRanked: Boolean,    // ranked choice poll
    creator: User.ID,
    createdAt: DateTime,
    numChoices: Int,      // size of choices seq, for safe rank updates
    answer: Option[String],
    reveal: Option[String],
    picks: Option[Ask.Picks],
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

  def hasPick(uid: User.ID): Boolean = picks.exists(_ contains uid)

  def getPick(uid: User.ID): Option[Int] =
    picks flatMap(_.get(uid) flatMap(_ headOption))

  def getRanking(uid: User.ID): Option[List[Int]] =
    picks flatMap(_.get(uid) flatMap(_ some))

  def count(choice: Int): Int    = picks.fold(0)(_.values.count(_.headOption contains choice))
  def count(choice: String): Int = count(choices indexOf choice)

  def whoPicked(choice: String): Seq[User.ID] = whoPicked(choices indexOf choice)
  def whoPicked(choice: Int): Seq[User.ID] =
    picks match {
      case Some(p) =>
        p.collect { case (text, i) if i.headOption.contains(choice) => text }.toSeq
      case None => Nil
    }

  def isPoll: Boolean = answer isEmpty

  def isQuiz: Boolean = answer nonEmpty
}

object Ask {
  val idSize = 8

  type ID      = String
  type Cookie  = String
  type Choices = IndexedSeq[String]
  type Picks   = Map[User.ID, List[Int]] // _2 is list of indices into Choices seq

  def make(
      _id: Option[String],
      question: String,
      choices: Choices,
      isPublic: Boolean,
      isTally: Boolean,
      isConcluded: Boolean,
      isRanked: Boolean,
      creator: User.ID,
      answer: Option[String],
      reveal: Option[String]
  ) =
    Ask(
      _id = _id.getOrElse(lila.common.ThreadLocalRandom nextString idSize),
      question = question,
      choices = choices,
      isPublic = isPublic,
      isTally = isTally,
      isConcluded = isConcluded,
      isRanked = isRanked,
      numChoices = choices.size,
      createdAt = DateTime.now(),
      creator = creator,
      answer = answer,
      reveal = reveal,
      picks = None,
      url = None
    )
}
