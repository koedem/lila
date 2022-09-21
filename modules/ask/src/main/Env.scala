package lila.ask

import com.softwaremill.macwire._
import com.softwaremill.tagging.@@
import lila.common.config._

@Module
final class Env(
    db: lila.db.AsyncDb @@ lila.db.YoloDb,
    timeline: lila.hub.actors.Timeline
)(implicit ec: scala.concurrent.ExecutionContext,
  scheduler: akka.actor.Scheduler
) {
  private lazy val coll = db(CollName("ask"))
  lazy val api          = wire[AskApi]
}
