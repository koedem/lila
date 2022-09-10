package lila.ask

import com.softwaremill.macwire._
import lila.common.config._

@Module
final class Env(
    db: lila.db.Db,
    timeline: lila.hub.actors.Timeline)
(implicit
    ec: scala.concurrent.ExecutionContext
) {
  private lazy val coll = db(CollName("ask"))
  lazy val api = wire[AskApi]
}