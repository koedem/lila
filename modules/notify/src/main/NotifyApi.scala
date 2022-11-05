package lila.notify

//import scala.collection.immutable.Iterable
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.Future
import lila.common.Bus
import lila.common.config.MaxPerPage
import lila.common.paginator.Paginator
import lila.common.String.shorten
import lila.db.dsl._
import lila.db.paginator.Adapter
import lila.hub.actorApi.notify.NotifyAllows
import lila.hub.actorApi.push._
import lila.hub.actorApi.socket.{SendTo, SendTos}
import lila.memo.CacheApi._
import lila.pref.NotificationPref.Allows
import lila.user.{User, UserRepo}
import lila.i18n._
import Notification._
import play.api.libs.json.Json

final class NotifyApi(
    jsonHandlers: JSONHandlers,
    repo: NotificationRepo,
    userRepo: UserRepo,
    cacheApi: lila.memo.CacheApi,
    maxPerPage: MaxPerPage,
    prefApi: lila.pref.PrefApi,
    getLightUser: lila.common.LightUser.Getter
)(implicit ec: scala.concurrent.ExecutionContext) {

  import BSONHandlers._

  def getNotifications(userId: User.ID, page: Int): Fu[Paginator[Notification]] =
    Paginator(
      adapter = new Adapter(
        collection = repo.coll,
        selector = repo.userNotificationsQuery(userId),
        projection = none,
        sort = repo.recentSort
      ),
      currentPage = page,
      maxPerPage = maxPerPage
    )

  def getNotificationsAndCount(userId: User.ID, page: Int): Fu[Notification.AndUnread] =
    getNotifications(userId, page) zip unreadCount(userId) dmap (Notification.AndUnread.apply _).tupled

  def markAllRead(userId: User.ID): Funit =
    repo.markAllRead(userId) >>- unreadCountCache.put(userId, fuccess(0))

  def markAllRead(userIds: Iterable[User.ID]): Funit =
    repo.markAllRead(userIds) >>- userIds.foreach {
      unreadCountCache.put(_, fuccess(0))
    }

  private val unreadCountCache = cacheApi[User.ID, Int](32768, "notify.unreadCountCache") {
    _.expireAfterAccess(15 minutes)
      .buildAsyncFuture(repo.unreadNotificationsCount)
  }

  def unreadCount(userId: User.ID): Fu[Int] =
    unreadCountCache get userId

  def insertNotification(notification: Notification): Funit =
    repo.insert(notification) >>- unreadCountCache.update(notification.to, _ + 1)

  def remove(notifies: User.ID, selector: Bdoc = $empty): Funit =
    repo.remove(notifies, selector) >>- unreadCountCache.invalidate(notifies)

  def markRead(notifies: User.ID, selector: Bdoc): Funit =
    repo.markManyRead(selector ++ $doc("notifies" -> notifies, "read" -> false)) >>-
      unreadCountCache.invalidate(notifies)

  def exists = repo.exists _

  def notifyOne(to: User.ID, content: NotificationContent): Funit = {
    val note = Notification.make(to, content)
    !shouldSkip(note) ifThen {
      insertNotification(note) >> {
        if (!Allows.canFilter(note.content.key)) publishOne(note)
        else getFilter(note.to, note.content.key) flatMap { x =>
          x.bell ?? publishOne(note)
          x.push ?? fuccess(pushOne(NotifyAllows(note.to, x.value), note.content))
        }
      }
    }
  }

  // notifyMany just informs clients that an update is availabe so they can bump their bell.  this avoids
  // having to assemble full notification pages for many clients at once
  def notifyMany(userIds: Iterable[String], content: NotificationContent): Funit =
    prefApi.getNotifyAllows(userIds, content.key) flatMap { notifyAllows =>
      val bells = notifyAllows collect { case x if Allows(x.allows).bell => x.userId }

      // bells map unreadCountCache.update(_, _ + 1)
      // or maybe update only if getIfPresent?  or just invalidate

      bells map unreadCountCache.invalidate
      repo.insertMany(bells map(x => Notification.make(x, content))) >>-
      Bus.publish(
        SendTos(
          bells.toSet,
          "notifications",
          Json.obj("incrementUnread" -> true)
        ),
        "socketUsers"
      )
    }

  private def publishOne(note: Notification): Funit =
    getNotifications(note.to, 1) zip unreadCount(note.to) dmap (AndUnread.apply _).tupled map { msg =>
      Bus.publish(
        SendTo.async(
          note.to,
          "notifications",
          () =>
            userRepo langOf note.to map I18nLangPicker.byStrOrDefault map (implicit lang =>
              jsonHandlers(msg)
            )
        ),
        "socketUsers"
      )
    }

  private def pushOne(to: NotifyAllows, content: NotificationContent) =
    pushMany(Seq(to), content)

  private def pushMany(to: Iterable[NotifyAllows], content: NotificationContent) = {
    Bus.publish(
      PushNotification(to, content),
      "pushNotify"
    )
  }

  private def shouldSkip(note: Notification): Fu[Boolean] =
    note.content match {
      case MentionedInThread(_, _, topicId, _, _) =>
        userRepo.isKid(note.to) >>|
          repo.hasRecent(note, 3.days, "content.topicId" -> topicId)
      case InvitedToStudy(_, _, studyId) =>
        userRepo.isKid(note.to) >>|
          repo.hasRecent(note, 3.days, "content.studyId" -> studyId)
      case PrivateMessage(sender, _) =>
        repo.hasRecentPrivateMessageFrom(note.to, sender)
      case _ => userRepo.isKid(note.to)
    }

  private def getFilter(uid: String, tpe: String): Fu[Allows] = {
    prefApi.getNotificationPref(uid) map(_ allows tpe)
  }
}
