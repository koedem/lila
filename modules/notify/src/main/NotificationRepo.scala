package lila.notify

import org.joda.time.DateTime
import scala.concurrent.duration.{ Duration, DurationInt }
import lila.db.dsl._
import lila.user.User
import reactivemongo.api.bson.ElementProducer

final private class NotificationRepo(
    val coll: Coll,
    val userRepo: lila.user.UserRepo,
    val prefApi: lila.pref.PrefApi
)(implicit ec: scala.concurrent.ExecutionContext) {

  import BSONHandlers._

  def insert(notification: Notification) =
    coll.insert.one(notification).void

  def insertMany(notifications: Iterable[Notification]): Funit =
    coll.insert.many(notifications).void

  def remove(notifies: User.ID, selector: Bdoc): Funit =
    coll.delete.one(userNotificationsQuery(notifies) ++ selector).void

  def markAllRead(notifies: User.ID): Funit =
    markManyRead(unreadOnlyQuery(notifies))

  def markAllRead(notifies: Iterable[User.ID]): Funit =
    markManyRead(unreadOnlyQuery(notifies))

  def markManyRead(doc: Bdoc): Funit =
    coll.update.one(doc, $set("read" -> true), multi = true).void

  def unreadNotificationsCount(userId: User.ID): Fu[Int] =
    coll.countSel(unreadOnlyQuery(userId))

  /*private def hasOld =
    $doc(
      "read" -> false,
      $or(
        $doc("content.type" -> "streamStart", "createdAt" $gt DateTime.now.minusHours(2)),
        $doc("content.type" -> $ne("streamStart"), "createdAt" $gt DateTime.now.minusDays(3))
      )
    )*/

  private def hasSince(since: Duration) =
    $doc("createdAt" $gt DateTime.now.minus(since.toMillis))

  private def hasUnreadSince(unreadSince: Duration) =
    $doc("read" -> false, "createdAt" $gt DateTime.now.minus(unreadSince.toMillis))

  private def hasRecentOrUnreadSince(since: Duration) = $or(hasUnreadSince(since), hasSince(10.minutes))

  // private def hasRecentOrUnread = hasRecentOrUnreadSince(3.days)
  // private def hasOldOrUnread =
  //  $doc("$or" -> List(hasOld, hasUnread))

  /*def hasRecentStudyInvitation(userId: User.ID, studyId: String): Fu[Boolean] =
    coll.exists(
      $doc(
        "notifies"        -> userId,
        "content.type"    -> "invitedStudy",
        "content.studyId" -> studyId
      ) ++ hasRecentOrUnread
    )

  // returns a list of users grouped by the same unread count. it's a bit more complex than
  // a 'RingBell' notification that just bumps the badge number by 1 at the client...
  // but need to be certain their view of the count is always correct to do that.
  def bulkUnreadCount(userIds: Iterable[User.ID]): Fu[List[(String, Int)]] = {
    coll.aggregateList(-1, ReadPreference.secondaryPreferred) { f =>
      f.Match($doc("notifies" $in userIds) ++ hasOld) ->
        List(f.GroupField("notifies")("nb" -> f.SumAll))
    } map { docs =>
      for {
        doc   <- docs
        user  <- doc.pp("just making sure this is lite") string "_id"
        count <- doc int "nb"
      } yield (user, count)
    }
  }*/

  /*def hasRecentNotificationsInThread(
      userId: User.ID,
      topicId: String
  ): Fu[Boolean] =
    coll.exists(
      $doc(
        "notifies"        -> userId,
        "content.type"    -> "mention",
        "content.topicId" -> topicId
      ) ++ hasUnread
    )*/
  def hasRecent(note: Notification, unreadSince: Duration, e: ElementProducer): Fu[Boolean] =
    coll.exists(
      $doc(
        "notifies"     -> note.notifies,
        "content.type" -> note.content.key,
        e
      ) ++ hasRecentOrUnreadSince(unreadSince)
    )
  def hasRecentPrivateMessageFrom(
      userId: User.ID,
      sender: String
  ): Fu[Boolean] =
    coll.exists(
      $doc(
        "notifies"     -> userId,
        "content.type" -> "privateMessage",
        "content.user" -> sender
      ) ++ hasUnreadSince(3.days)
    )

  def exists(notifies: User.ID, selector: Bdoc): Fu[Boolean] =
    coll.exists(userNotificationsQuery(notifies) ++ selector)

  val recentSort = $sort desc "createdAt"

  def mostRecentUnread(userId: User.ID) =
    coll.find($doc("notifies" -> userId, "read" -> false)).sort($sort.createdDesc).one[Notification]

  def userNotificationsQuery(userId: User.ID) = $doc("notifies" -> userId)

  private def unreadOnlyQuery(userId: User.ID) = $doc("notifies" -> userId, "read" -> false)
  private def unreadOnlyQuery(userIds: Iterable[User.ID]) =
    $doc("notifies" $in userIds, "read" -> false)

  /*private def lookupNotifiable( eventClass: String,
                                userIds: List[User.ID]
                              ): Fu[List[NotifyAllows]] = {
    prefApi.coll
      .aggregateList(-1, ReadPreference.secondaryPreferred) { framework =>
        import framework._
        Match($inIds(userIds) ++ $doc(s"notification.$eventClass" -> $doc("$gt" -> 0))) ->
          List(Project($doc(s"notification.$eventClass" -> true)))
      }
      .map { docs =>
        for {
          doc     <- docs
          id      <- doc string "_id"
          filter  <- doc child "notification" map (_ int eventClass)
        } yield NotifyAllows(id, ~filter)
      }
  }
  case class NotifiableUser(userId: User.ID, allows: Allows, recentlyOnline: Boolean)

  private def lookupNotifiable( eventClass: String,
                                userIds: List[User.ID]
                              ): Fu[List[NotifiableUser]] = {
    prefApi.coll
      .aggregateList(-1, ReadPreference.secondaryPreferred) { framework =>
        import framework._
        Match($inIds(userIds) ++ $doc(s"notification.$eventClass" -> $doc("$gt" -> 0))) ->
          List(
            Project($doc(s"notification.$eventClass" -> true)),
            PipelineOperator(
              $lookup.pipeline(
                from = userRepo.coll,
                as = "u",
                local = "_id",
                foreign = "_id",
                pipe = List(
                  $doc("$match"   -> $doc("enabled" -> true)),
                  $doc("$project" -> $doc("seenAt" -> true, "_id" -> false))
                )
              )
            ),
            Unwind("u"),
            Sort(Descending("u.seenAt"))
          )
      }
      .map { docs =>
        for {
          doc     <- docs
          id      <- doc string "_id"
          filter  <- doc child "notification" map (_ int eventClass)
          seenAt  <- doc child "u" map (_.getAsOpt[DateTime]("seenAt"))

          recentlyOnline = seenAt.fold(false)(_.compareTo(DateTime.now().minusMinutes(15)) > 0)
        } yield NotifiableUser(id, Allows(~filter), recentlyOnline)
      }
  }*/
}
