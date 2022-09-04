package lila.ask

import lila.user.User
import org.joda.time.DateTime

case class Ask(
    _id: Ask.ID,
    question: String,
    choices: Ask.Choices,
    isPublic: Boolean,    // users picks are not secret
    isTally: Boolean,     // results visible before closing
    isConcluded: Boolean, // no more picks
    creator: User.ID,
    createdAt: DateTime,
    answer: Option[String],
    reveal: Option[String],
    picks: Option[Ask.Picks],
    url: Option[String]
) {

  def invalidatedBy(p: Ask): Boolean =
    question != p.question ||
      choices != p.choices ||
      isPublic != p.isPublic ||
      answer != p.answer ||
      reveal != p.reveal

  def participants: Seq[User.ID] =
    picks match {
      case Some(p) => p.keys.toSeq
      case None    => Nil
    }

  def hasPick(uid: User.ID): Boolean = picks.exists(_.contains(uid))

  def getPick(uid: User.ID): Option[Int] =
    picks flatMap (p => p.get(uid).flatMap(v => Some(v)))

  def count(choice: Int): Int    = picks.fold(0)(_.values.count(_ == choice))
  def count(choice: String): Int = count(choices.indexOf(choice))

  def whoPicked(choice: String): Seq[User.ID] = whoPicked(choices.indexOf(choice))
  def whoPicked(choice: Int): Seq[User.ID] =
    picks match {
      case Some(p) =>
        p.collect { case (text, i) if choice == i => text }.toSeq
      case None => Nil
    }

  def isPoll: Boolean = answer.isEmpty

  def isQuiz: Boolean = answer.nonEmpty
}

object Ask {
  val idSize = 8

  type ID      = String
  type Cookie  = String
  type Choices = IndexedSeq[String]
  type Picks   = Map[User.ID, Int] // _2 is index in Choices list

  def make(
      _id: Option[String],
      question: String,
      choices: Choices,
      isPublic: Boolean,
      isTally: Boolean,
      isConcluded: Boolean,
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
      createdAt = DateTime.now(),
      creator = creator,
      answer = answer,
      reveal = reveal,
      picks = None,
      url = None
    )
}
