package lila.streamer

import play.api.libs.json._
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.{StandaloneWSClient, StandaloneWSResponse}

import scala.concurrent.ExecutionContext
import lila.common.config.Secret

object TwitchApi {
  case class ChannelInfo(
      login: String, // channel owner login (also channel name in url)
      displayName: String, // channel owner displayName
      id: String, // channel owner id
      description: String, // owner description
      viewCount: Int,
      profileImageUrl: String, // can use this?
      lang: String, // ISO 639-1 two char code or "other"
      title: String, // title of current stream (should have lichess.org)
      gameId: String, // game id current stream (should be "743")
      gameName: String, // should be "Chess"
  )
}

final /* private */ class TwitchApi(ws: StandaloneWSClient, config: TwitchConfig)(implicit ec: ExecutionContext) {

  import Stream.Twitch
  import Twitch.Reads._
  import TwitchApi._

  /* fix this */
  private var lastTokenValidationTime = 0L
  private var token = Secret("0jezcjwfkdk9en3uuvck6s9vxtoct5")
  lastTokenValidationTime = Long.MaxValue - 3600 * 1000//0L
  /* end fix this */

  def fetchStreams(
      streamers: List[Streamer],
      page: Int,
      pagination: Option[Twitch.Pagination]
  ): Fu[List[Twitch.TwitchStream]] =
    (enabled && page < 10) ?? {
      val query = List(
        "game_id" -> "743", // chess
        "first"   -> "100"  // max results per page
      ) ::: List(
        pagination.flatMap(_.cursor).map { "after" -> _ }
      ).flatten
      validateToken >> ws.url("https://api.twitch.tv/helix/streams")
        .withQueryStringParameters(query: _*)
        .withHttpHeaders(
          "Client-ID"     -> config.clientId,
          "Authorization" -> s"Bearer ${token.value}"
        )
        .get()
        .flatMap {
          case res if res.status == 200 =>
            res.body[JsValue].validate[Twitch.Result](twitchResultReads) match {
              case JsSuccess(result, _) => fuccess(result)
              case JsError(err)         => fufail(s"twitch $err ${lila.log.http(res.status, res.body)}")
            }
          case res if res.status == 401 && res.body.contains("Invalid OAuth token") =>
            logger.warn("Renewing twitch API token")
            renewToken >> fuccess(Twitch.Result(None, None))
          case res => fufail(s"twitch ${lila.log.http(res.status, res.body)}")
        }
        .recover { case e: Exception =>
          logger.warn(e.getMessage)
          Twitch.Result(None, None)
        }
        .monSuccess(_.tv.streamer.twitch)
        .flatMap { result =>
          if (result.data.exists(_.nonEmpty))
            fetchStreams(streamers, page + 1, result.pagination) map (result.liveStreams ::: _)
          else fuccess(Nil)
        }
    }

  def channelInfo(channel: String): Fu[Option[ChannelInfo]] =
    twitchRequest("helix/users", "login" -> channel) flatMap {
      case None => fuccess(None)
      case Some(userBody) =>
        val userData = userBody \ "data" \ 0
        val id = ~(userData \ "id").asOpt[String]
        twitchRequest("helix/channels", "broadcaster_id" -> id) collect {
          case Some(channelBody) =>
            val channelData = channelBody \ "data" \ 0
            ChannelInfo(
              login = channel,
              displayName = ~(userData \ "display_name").asOpt[String],
              id = id,
              description = ~(userData \ "description").asOpt[String],
              profileImageUrl = ~(userData \ "profile_image_url").asOpt[String],
              viewCount = ~(userData \ "view_count").asOpt[Int],
              lang = ~(channelData \ "broadcaster_language").asOpt[String],
              title = ~(channelData \ "title").asOpt[String],
              gameId = ~(channelData \ "game_id").asOpt[String],
              gameName = ~(channelData \ "game_name").asOpt[String]
            ).some
        }
    }

  private def enabled: Boolean = config.clientId.nonEmpty && config.secret.value.nonEmpty

  private def twitchRequest(path: String, params: (String, String)*): Fu[Option[JsValue]] =
    enabled ?? validateToken >>
      ws.url(s"https://api.twitch.tv/$path")
        .withQueryStringParameters( params: _*)
        .withHttpHeaders("Client-Id" -> config.clientId, "Authorization" -> s"Bearer ${token.value}")
      .get() collect {
        case x if x.status == 200 => x.body[JsValue].some
      }

  private def validateToken: Funit =
    (System.currentTimeMillis() > lastTokenValidationTime + 3600 * 1000) ?? {
      lastTokenValidationTime = System.currentTimeMillis()
      ws.url("https://id.twitch.tv/oauth2/validate")
        .withHttpHeaders("authorization" -> s"OAuth ${token.value}")
        .get() flatMap {
          case res if res.status == 200 => funit
          case res if res.status == 401 => renewToken
          case res => fufail(s"twitch.validateToken ${lila.log.http(res.status, res.body)}")
        }
    }

  private def renewToken: Funit =
    ws.url("https://id.twitch.tv/oauth2/token")
      .withQueryStringParameters(
        "client_id"     -> config.clientId,
        "client_secret" -> config.secret.value,
        "grant_type"    -> "client_credentials"
      )
      .post(Map.empty[String, String])
      .flatMap {
        case res if res.status == 200 =>
          res.body[JsValue].asOpt[JsObject].flatMap(_ str "access_token") match {
            case Some(twitchToken) =>
              token = Secret(twitchToken)
              funit
            case _ => fufail(s"twitch.renewToken ${lila.log.http(res.status, res.body)}")
          }
        case res => fufail(s"twitch.renewToken ${lila.log.http(res.status, res.body)}")
      }
}
