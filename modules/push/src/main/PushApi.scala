package lila.push

import play.api.libs.json._

import scala.concurrent.duration._
import lila.challenge.Challenge
import lila.common.String.shorten
import lila.common.{ Future, LightUser }
import lila.game.{ Game, Namer, Pov }
import lila.hub.actorApi.map.Tell
import lila.hub.actorApi.push.TourSoon
import lila.hub.actorApi.round.{ IsOnGame, MoveEvent }
import lila.notify._
import lila.pref.{ Allows, NotificationPref, NotifyAllows }
import lila.user.User

final private class PushApi(
    firebasePush: FirebasePush,
    webPush: WebPush,
    userRepo: lila.user.UserRepo,
    implicit val lightUser: LightUser.Getter,
    proxyRepo: lila.round.GameProxyRepo,
    gameRepo: lila.game.GameRepo,
    prefApi: lila.pref.PrefApi,
    postApi: lila.forum.PostApi
)(implicit
    ec: scala.concurrent.ExecutionContext,
    scheduler: akka.actor.Scheduler
) {
  private[push] def notifyPush(
      to: Iterable[NotifyAllows],
      content: NotificationContent,
      params: Iterable[(String, String)]
  ): Funit = content match {
    case PrivateMessage(sender, text) =>
      lightUser(sender) flatMap (_ ?? (lsender =>
        privateMessage(to.head, sender, lsender.titleName, shorten(text, 57 - 3, "..."))
      ))
    case MentionedInThread(mentioner, topic, _, _, postId) =>
      lightUser(mentioner) flatMap (_ ?? (lmentioner =>
        forumMention(to.head, lmentioner.titleName, topic, postId)
      ))
    case StreamStart(streamerId, streamerName) =>
      streamStart(to, streamerId, streamerName)
    case _ => funit
  }

  def finish(game: Game): Funit =
    if (!game.isCorrespondence || game.hasAi) funit
    else
      game.userIds
        .map { userId =>
          Pov.ofUserId(game, userId) ?? { pov =>
            IfAway(pov) {
              gameRepo.countWhereUserTurn(userId) flatMap { nbMyTurn =>
                asyncOpponentName(pov) flatMap { opponent =>
                  maybePush(
                    userId,
                    _.finish,
                    NotificationPref.GameEvent,
                    PushApi.Data(
                      title = pov.win match {
                        case Some(true)  => "You won!"
                        case Some(false) => "You lost."
                        case _           => "It's a draw."
                      },
                      body = s"Your game with $opponent is over.",
                      stacking = Stacking.GameFinish,
                      payload = Json.obj(
                        "userId" -> userId,
                        "userData" -> Json.obj(
                          "type"   -> "gameFinish",
                          "gameId" -> game.id,
                          "fullId" -> pov.fullId
                        )
                      ),
                      iosBadge = nbMyTurn.some.filter(0 <)
                    )
                  )
                }
              }
            }
          }
        }
        .sequenceFu
        .void

  def move(move: MoveEvent): Funit =
    Future.delay(2 seconds) {
      proxyRepo.game(move.gameId) flatMap {
        _.filter(_.playable) ?? { game =>
          val pov = Pov(game, game.player.color)
          game.player.userId ?? { userId =>
            IfAway(pov) {
              gameRepo.countWhereUserTurn(userId) flatMap { nbMyTurn =>
                asyncOpponentName(pov) flatMap { opponent =>
                  game.pgnMoves.lastOption ?? { sanMove =>
                    maybePush(
                      userId,
                      _.move,
                      NotificationPref.GameEvent,
                      PushApi.Data(
                        title = "It's your turn!",
                        body = s"$opponent played $sanMove",
                        stacking = Stacking.GameMove,
                        payload = Json.obj(
                          "userId"   -> userId,
                          "userData" -> corresGameJson(pov, "gameMove")
                        ),
                        iosBadge = nbMyTurn.some.filter(0 <)
                      )
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

  def takebackOffer(gameId: Game.ID): Funit =
    Future.delay(1 seconds) {
      proxyRepo.game(gameId) flatMap {
        _.filter(_.playable).?? { game =>
          game.players.collectFirst {
            case p if p.isProposingTakeback => Pov(game, game opponent p)
          } ?? { pov => // the pov of the receiver
            pov.player.userId ?? { userId =>
              IfAway(pov) {
                asyncOpponentName(pov) flatMap { opponent =>
                  maybePush(
                    userId,
                    _.takeback,
                    NotificationPref.GameEvent,
                    PushApi
                      .Data(
                        title = "Takeback offer",
                        body = s"$opponent proposes a takeback",
                        stacking = Stacking.GameTakebackOffer,
                        payload = Json.obj(
                          "userId"   -> userId,
                          "userData" -> corresGameJson(pov, "gameTakebackOffer")
                        )
                      )
                  )
                }
              }
            }
          }
        }
      }
    }

  def drawOffer(gameId: Game.ID): Funit =
    Future.delay(1 seconds) {
      proxyRepo.game(gameId) flatMap {
        _.filter(_.playable).?? { game =>
          game.players.collectFirst {
            case p if p.isOfferingDraw => Pov(game, game opponent p)
          } ?? { pov => // the pov of the receiver
            pov.player.userId ?? { userId =>
              IfAway(pov) {
                asyncOpponentName(pov) flatMap { opponent =>
                  maybePush(
                    userId,
                    _.takeback,
                    NotificationPref.GameEvent,
                    PushApi.Data(
                      title = "Draw offer",
                      body = s"$opponent offers a draw",
                      stacking = Stacking.GameDrawOffer,
                      payload = Json.obj(
                        "userId"   -> userId,
                        "userData" -> corresGameJson(pov, "gameDrawOffer")
                      )
                    )
                  )
                }
              }
            }
          }
        }
      }
    }

  def corresAlarm(pov: Pov): Funit =
    pov.player.userId ?? { userId =>
      asyncOpponentName(pov) flatMap { opponent =>
        maybePush(
          userId,
          _.corresAlarm,
          NotificationPref.GameEvent,
          PushApi.Data(
            title = "Time is almost up!",
            body = s"You are about to lose on time against $opponent",
            stacking = Stacking.GameMove,
            payload = Json.obj(
              "userId"   -> userId,
              "userData" -> corresGameJson(pov, "corresAlarm")
            )
          )
        )
      }
    }

  private def corresGameJson(pov: Pov, typ: String) =
    Json.obj(
      "type"   -> typ,
      "gameId" -> pov.gameId,
      "fullId" -> pov.fullId
    )

  def privateMessage(to: NotifyAllows, senderId: String, senderName: String, text: String): Funit =
    userRepo.isKid(to.userId) flatMap {
      !_ ?? {
        filterPush(
          to.userId,
          _.message,
          to.allows,
          PushApi.Data(
            title = senderName,
            body = text,
            stacking = Stacking.PrivateMessage,
            payload = Json.obj(
              "userId" -> to.userId,
              "userData" -> Json.obj(
                "type"     -> "newMessage",
                "threadId" -> senderId
              )
            )
          )
        )
      }
    }

  def challengeCreate(c: Challenge): Funit =
    c.destUser ?? { dest =>
      c.challengerUser.ifFalse(c.hasClock) ?? { challenger =>
        lightUser(challenger.id) flatMap {
          _ ?? { lightChallenger =>
            maybePush(
              dest.id,
              _.challenge.create,
              NotificationPref.Challenge,
              PushApi.Data(
                title = s"${lightChallenger.titleName} (${challenger.rating.show}) challenges you!",
                body = describeChallenge(c),
                stacking = Stacking.ChallengeCreate,
                payload = Json.obj(
                  "userId" -> dest.id,
                  "userData" -> Json.obj(
                    "type"        -> "challengeCreate",
                    "challengeId" -> c.id
                  )
                )
              )
            )
          }
        }
      }
    }

  def challengeAccept(c: Challenge, joinerId: Option[String]): Funit =
    c.challengerUser.ifTrue(c.finalColor.white && !c.hasClock) ?? { challenger =>
      joinerId ?? lightUser flatMap { lightJoiner =>
        maybePush(
          challenger.id,
          _.challenge.accept,
          NotificationPref.Challenge,
          PushApi.Data(
            title = s"${lightJoiner.fold("Anonymous")(_.titleName)} accepts your challenge!",
            body = describeChallenge(c),
            stacking = Stacking.ChallengeAccept,
            payload = Json.obj(
              "userId" -> challenger.id,
              "userData" -> Json.obj(
                "type"        -> "challengeAccept",
                "challengeId" -> c.id
              )
            )
          )
        )
      }
    }

  def tourSoon(tour: TourSoon): Funit =
    lila.common.Future.applySequentially(tour.userIds.toList) { userId =>
      maybePush(
        userId,
        _.tourSoon,
        NotificationPref.TournamentSoon,
        PushApi
          .Data(
            title = tour.tourName,
            body = "The tournament is about to start!",
            stacking = Stacking.ChallengeAccept,
            payload = Json
              .obj(
                "userId" -> userId,
                "userData" -> Json.obj(
                  "type"     -> "tourSoon",
                  "tourId"   -> tour.tourId,
                  "tourName" -> tour.tourName,
                  "path"     -> s"/${if (tour.swiss) "swiss" else "tournament"}/${tour.tourId}"
                )
              )
          )
      )
    }

  def forumMention(to: NotifyAllows, mentionedBy: String, topic: String, postId: String): Funit =
    postApi.getPost(postId) flatMap { post =>
      to.userId.pp("forumMention")
      filterPush(
        to.userId,
        _.forumMention,
        to.allows,
        PushApi.Data(
          title = topic,
          body = post.fold(topic)(p => shorten(p.text, 57 - 3, "...")),
          stacking = Stacking.ForumMention,
          payload = Json.obj(
            "userId" -> to.userId,
            "userData" -> Json.obj(
              "type"        -> "forumMention",
              "mentionedBy" -> mentionedBy,
              "topic"       -> topic,
              "postId"      -> postId
            )
          )
        )
      )
    }

  def streamStart(recips: Iterable[NotifyAllows], streamerId: User.ID, streamerName: String): Funit = {
    val pushData = PushApi.Data(
      title = streamerName,
      body = streamerName + " started streaming",
      stacking = Stacking.StreamStart,
      payload = Json.obj("userData" -> Json.obj("type" -> "streamStart", "streamerId" -> streamerId))
    )
    webPush(recips collect { case u if u.web => u.userId }, pushData) >>- {
      // TODO - for device push, register topic membership for user devices from Controllers/Streamer.scala
      // subscribe/unsubscribe methods and push a single message to "streamer.$streamerId" topic.  this will
      // cause some complications dealing with prefs when a user unsubscribes from streamer push.  prefs do
      // not currently have hooks to trigger anything when a setting changes.  sequential for now
      recips collect { case u if u.device => u.userId } foreach(firebasePush(_, pushData))
    }
  }

  private type MonitorType = lila.mon.push.send.type => ((String, Boolean) => Unit)

  private def maybePush(
      userId: User.ID,
      monitor: MonitorType,
      event: NotificationPref.Event,
      data: PushApi.Data
  ): Funit =
    prefApi.getNotificationPref(userId) flatMap { x => filterPush(userId, monitor, x.allows(event), data) }

  private def filterPush(userId: User.ID, monitor: MonitorType, allows: Allows, data: PushApi.Data): Funit = {
    allows.web.pp(s"$userId ${data.body} allows.web") ?? webPush(userId, data).addEffects { res =>
      monitor(lila.mon.push.send)("web", res.isSuccess)
    }
    allows.device.pp(s"$userId ${data.body} allows.device") ?? firebasePush(userId, data).addEffects { res =>
      monitor(lila.mon.push.send)("firebase", res.isSuccess)
    }
  }

  private def describeChallenge(c: Challenge) = {
    import lila.challenge.Challenge.TimeControl._
    List(
      c.mode.fold("Casual", "Rated"),
      c.timeControl match {
        case Unlimited         => "Unlimited"
        case Correspondence(d) => s"$d days"
        case c: Clock          => c.show
      },
      c.variant.name
    ) mkString " • "
  }

  private def IfAway(pov: Pov)(f: => Funit): Funit =
    lila.common.Bus.ask[Boolean]("roundSocket") { p =>
      Tell(pov.gameId, IsOnGame(pov.color, p))
    } flatMap {
      case true  => funit
      case false => f
    }

  private def asyncOpponentName(pov: Pov): Fu[String] = Namer playerText pov.opponent
}

private object PushApi {
  case class Data(
      title: String,
      body: String,
      stacking: Stacking,
      payload: JsObject,
      iosBadge: Option[Int] = None
  )
}
