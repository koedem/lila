package lila.notify

import scala.concurrent.duration._
import scala.concurrent.Future
import lila.common.Bus
import lila.common.config.MaxPerPage
import lila.common.paginator.Paginator
import lila.common.String.shorten
import lila.db.dsl._
import lila.db.paginator.Adapter
import lila.hub.actorApi.socket.{SendTo, SendTos}
import lila.hub.actorApi.push._
import lila.memo.CacheApi._
import lila.user.UserRepo
import lila.i18n._
import Notification._

final class NotifyApi(
    jsonHandlers: JSONHandlers,
    repo: NotificationRepo,
    userRepo: UserRepo,
    cacheApi: lila.memo.CacheApi,
    streamStarter: StreamStartHelper,
    maxPerPage: MaxPerPage,
    prefApi : lila.pref.PrefApi,
    getLightUser: lila.common.LightUser.Getter
)(implicit ec: scala.concurrent.ExecutionContext) {

  import BSONHandlers.{ NotificationBSONHandler, NotifiesHandler }

  def getNotifications(userId: Notifies, page: Int): Fu[Paginator[Notification]] =
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

  def getNotificationsAndCount(userId: Notifies, page: Int): Fu[Notification.AndUnread] =
    getNotifications(userId, page) zip unreadCount(userId) dmap (Notification.AndUnread.apply _).tupled

  def markAllRead(userId: Notifies) =
    repo.markAllRead(userId) >>- unreadCountCache.put(userId, fuccess(0))

  def markAllRead(userIds: Iterable[Notifies]) =
    repo.markAllRead(userIds) >>- userIds.foreach {
      unreadCountCache.put(_, fuccess(0))
    }

  private val unreadCountCache = cacheApi[Notifies, Int](32768, "notify.unreadCountCache") {
    _.expireAfterAccess(15 minutes)
      .buildAsyncFuture(repo.unreadNotificationsCount)
  }

  def unreadCount(userId: Notifies): Fu[UnreadCount] =
    unreadCountCache get userId dmap UnreadCount.apply

  def addNotification(notification: Notification): Fu[Boolean] =
    // Add to database and then notify any connected clients of the new notification
    insertOrDiscardNotification(notification) map {
      case Some(note) =>
        notifyUser(note)
        true
      case None => false.pp("oh noes oh noes")
    }

  def addNotificationWithoutSkipOrEvent(notification: Notification): Funit =
    repo.insert(notification) >>- unreadCountCache.update(notification.notifies, _ + 1)

  def addNotifications(notifications: List[Notification]): Funit =
    notifications.map(addNotification).sequenceFu.void

  def remove(notifies: Notifies, selector: Bdoc = $empty): Funit =
    repo.remove(notifies, selector) >>- unreadCountCache.invalidate(notifies)

  def markRead(notifies: Notifies, selector: Bdoc): Funit =
    repo.markManyRead(selector ++ $doc("notifies" -> notifies, "read" -> false)) >>-
      unreadCountCache.invalidate(notifies)

  def exists = repo.exists _

  def notifyStreamStart(streamerId: String, streamerName: String): Funit =
    streamStarter.getNotiflowersAndPush(streamerId, streamerName) flatMap { res =>
      val views = streamStarter.NotiflowerView(res, streamerId, streamerName)
      views.pp("NotiflowerView")
      repo.bulkUnreadCount(views.users) flatMap { countList =>
        bumpCountCache(countList filter (recent => views.byUser(recent._1).recentlyOnline))

        repo.insertMany(views.noteList) andThen { case _ =>
          countList.toMap groupBy(_._2) map (group => (group._2.keySet, group._1)) map { case (users, count) =>
            sendTos(users, count)
          }
        }
      }
    }

  private def sendTos(users: Set[String], count: Int) =
    Bus.publish(
      SendTos(
        users,
        "notifications",
        jsonHandlers(Notification.UpdateBell(UnreadCount(count + 1)))
      ),
      "socketUsers"
    )

  private def notesByCount(countList: List[(String, Int)]): List[(Set[String], Int)] =
    countList.toMap groupBy(_._2) map (x => (x._2.keySet, x._1)) toList

  private def bumpCountCache(countList: List[(String, Int)]) =
    countList map { case (userId, veryRecentCount) =>
      // we have the accurate count.  and everyone in countList is recently online
      unreadCountCache.put(Notifies(userId), Future({ veryRecentCount + 1 })) // +1 for the streamStart
    }

  private def shouldSkip(notification: Notification) = 
    (!notification.isMsg ?? userRepo.isKid(notification.notifies.value)) >>| {
      notification.content match {
        case MentionedInThread(_, _, topicId, _, _) =>
          repo.hasRecentNotificationsInThread(notification.notifies, topicId)
        case InvitedToStudy(_, _, studyId) => repo.hasRecentStudyInvitation(notification.notifies, studyId)
        case PrivateMessage(sender, _)     => repo.hasRecentPrivateMessageFrom(notification.notifies, sender)
        case _                             => fuFalse
      }
    }

  /** Inserts notification into the repository. If the user already has an unread notification on the topic,
    * discard it. If the user does not already have an unread notification on the topic, returns it
    * unmodified.
    */
  private def insertOrDiscardNotification(notification: Notification): Fu[Option[Notification]] =
    !shouldSkip(notification) flatMap {
      case true  => addNotificationWithoutSkipOrEvent(notification) inject notification.some
      case false => fuccess(None)
    }

  private def notifyUser(note: Notification) =
    getAllows(note.notifies.value, note) map { allows =>
      if (allows.push) pushToUser(note)
      if (allows.bell) {
        val u = note.notifies
        getNotifications(u, 1) zip unreadCount(u) dmap (AndUnread.apply _).tupled map { msg =>
          Bus.publish(
            SendTo.async(
              u.value, 
              "notifications", 
              () => userRepo langOf u.value map I18nLangPicker.byStrOrDefault map(implicit lang => jsonHandlers(msg))                
            ),
            "socketUsers"
          )
        }
      }
    }

  private def pushToUser(note: Notification) = {
    note.content match {
      case PrivateMessage(sender: PrivateMessage.Sender, text:PrivateMessage.Text) =>
        getLightUser(sender.value) map {
          case Some(luser) =>
            Bus.publish(
              InboxMsg(
                note.notifies.value,
                luser.id,
                luser.titleName,
                shorten(text.value, 57 - 3, "...")
              ),
              "msgUnread"
            )
          case _ =>
        }
      case MentionedInThread(commenter, topic, _, _, postId) =>
        userRepo.langOf(note.notifies.value) collect { case langOption =>
          implicit val lang: play.api.i18n.Lang = I18nLangPicker.byStrOrDefault(langOption)
          Bus.publish(
            ForumMention(
              note.notifies.value,
              I18nKeys.xMentionedYouInY.txt(commenter, topic),
              postId.value
            ),
            "forumMention"
          )
        }
      case _ =>
    }
  }

  import lila.pref.NotificationPref._
  private def getAllows(uid: String, note: Notification): Fu[Allows] =
    prefApi.getNotificationPref(uid) map { pref => pref.pp("full notificationPref")
      note.content match {
        case _: PrivateMessage => pref.allows(InboxMsg).pp("inbox msg!")
        case _: MentionedInThread => pref.allows(ForumMention).pp("forum mention!")
        case _ => Allows(BELL)
      }
    }
}
