package lila.pref

import play.api.libs.json.{ Json, OWrites }
import reactivemongo.api.bson.Macros
import NotificationPref._

// take care with NotificationPref field names - they map directly to db and ws channels

case class NotificationPref(
    privateMessage: Allows,
    challenge: Allows,
    mention: Allows,
    streamStart: Allows,
    tournamentSoon: Allows,
    gameEvent: Allows,
    inviteStudy: Allows,
    titledTourney: Allows,
    correspondenceEmail: Int
) {

  def allows(event: Event): Allows = allows(event.toString)

  def allows(tpe: String): Allows = {
    val (key, values) = (this.productElementNames, this.productIterator)
    while (key.hasNext) {
      val value = values.next()
      if (key.next() == tpe) return value.asInstanceOf[Allows]
    }
    Allows(0)
  }
}

object NotificationPref {
  val BELL   = 1
  val WEB    = 2
  val DEVICE = 4
  val PUSH   = WEB | DEVICE

  case class Allows(value: Int) extends AnyVal with IntValue {
    def push: Boolean   = (value & PUSH) != 0
    def web: Boolean    = (value & WEB) != 0
    def device: Boolean = (value & DEVICE) != 0
    def bell: Boolean   = (value & BELL) != 0
    def any: Boolean    = value != 0
  }

  sealed trait Event {
    override def toString: String = { // for matching db fields, channels
      val typeName = getClass.getSimpleName
      s"${typeName.charAt(0).toLower}${typeName.substring(1, typeName.length - 1)}" // strip $
    }
  }

  case object PrivateMessage       extends Event
  case object Challenge      extends Event
  case object Mention   extends Event
  case object InviteStudy extends Event
  case object TitledTourney extends Event
  case object StreamStart    extends Event
  case object TournamentSoon extends Event
  case object GameEvent      extends Event

  lazy val default: NotificationPref = NotificationPref(
    privateMessage = Allows(BELL | PUSH),
    challenge = Allows(BELL | PUSH),
    titledTourney = Allows(BELL | PUSH),
    inviteStudy = Allows(BELL),
    mention = Allows(BELL),
    streamStart = Allows(BELL),
    tournamentSoon = Allows(PUSH),
    gameEvent = Allows(PUSH),
    correspondenceEmail = 0
  )

  implicit private val AllowsBSONHandler =
    lila.db.dsl.intAnyValHandler[Allows](_.value, Allows.apply)

  implicit val NotificationPrefBSONHandler =
    Macros.handler[NotificationPref]

  object Allows {

    def canFilter(tpe: String): Boolean = tpe match {
      case "privateMessage" => true
      case "challenge" => true
      case "mention" => true
      case "streamStart" => true
      case "tournamentSoon" => true
      case "gameEvent" => true
      case "titledTourney" => false // for now
      case "inviteStudy" => false // for now
      case _ => false
    }

    def fromForm(bell: Boolean, push: Boolean): Allows =
      Allows((bell ?? BELL) | (push ?? PUSH))

    def toForm(allows: Allows): Some[(Boolean, Boolean)] =
      Some((allows.bell, allows.push))

    //def apply(value: Int) = new Allows(value)
    //def unapply(allows: Allows): Int = allows.value
  }

  implicit val notificationDataJsonWriter: OWrites[NotificationPref] =
    OWrites[NotificationPref] { data =>
      Json.obj(
        "privateMessage"            -> allowsToJson(data.privateMessage),
        "mention"        -> allowsToJson(data.mention),
        "streamStart"         -> allowsToJson(data.streamStart),
        "challenge"           -> allowsToJson(data.challenge),
        "tournamentSoon"      -> allowsToJson(data.tournamentSoon),
        "gameEvent"           -> allowsToJson(data.gameEvent),
        "inviteStudy"         -> allowsToJson(data.inviteStudy),
        "titledTourney"       -> allowsToJson(data.titledTourney),
        "correspondenceEmail" -> (data.correspondenceEmail != 0)
      )
    }

  private def allowsToJson(v: Allows) = List(
    Map(BELL -> "bell", PUSH -> "push") collect {
      case (tpe, str) if (v.value & tpe) != 0 => str
    }
  )
}
