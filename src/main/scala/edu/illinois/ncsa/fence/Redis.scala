package edu.illinois.ncsa.fence

import _root_.java.lang.{Long => JLong}
import java.util.{Calendar, UUID}

import com.twitter.common.base.Command
import com.twitter.conversions.time._
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.protocol.{Limit, ZInterval}
import com.twitter.finagle.redis.util.{BufToString, _}
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util.{Await, Future}
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server._
import edu.illinois.ncsa.fence.models.{BytesStats, Stats}
import org.jboss.netty.buffer.ChannelBuffer

import scala.util.{Failure, Success, Try}

/** Client to redis. */
object Redis {

  private val conf = ConfigFactory.load()

  private val tokenNamespace = "token:"
  private val apiKeyNamespace = "key:"
  private val userNamespace = "user:"
  private val stats = "stats:"
  private val eventNamespace = "event:"
  private val host = conf.getString("redis.host")+":"+conf.getString("redis.port")

  val redis = Client(
    ClientBuilder()
      .hosts(host)
      .hostConnectionLimit(10)
      .codec(com.twitter.finagle.redis.Redis())
      .daemon(true)
      .buildFactory())

  /** Create a token based on a key. Time to live is set based on configuration file. */
  def createToken(key: UUID): UUID = {
    val token = Token.newToken()
    val ttl = conf.getLong("token.ttl")
    // Store token => key
    redis.setEx(StringToBuf(tokenNamespace+token.toString), ttl, StringToBuf(key.toString))
    // Store key => tokens
    redis.sAdd(StringToChannelBuffer(apiKeyNamespace+key+":tokens"), List(StringToChannelBuffer(token.toString)))
    token
  }

  /** Check token's time to live. */
  def checkToken(token: UUID): Future[Option[JLong]] = {
    redis.ttl(StringToBuf(tokenNamespace+token.toString))
  }

  /** Get user from token. */
  def getUser(token: UUID): Future[Option[String]] = {
    val tokenBuf = StringToBuf(tokenNamespace+token.toString)
    redis.get(tokenBuf).flatMap{
      case Some(apiKeyBuf: Buf) =>
        val apiKey = BufToString(apiKeyBuf)
        val apiKeyRedisKey = StringToBuf(apiKeyNamespace + apiKey)
        redis.get(apiKeyRedisKey).flatMap {
          case Some(usernameBuf: Buf) =>
            val user = BufToString(usernameBuf)
            Future.value(Some(user))
          case None =>
            Future.value(None)
        }
      case None =>
        Future.value(None)
    }
  }

  /** Delete token and update all redis key values. */
  def deleteToken(token: UUID): Boolean = {
    // find key for token
    val maybeKey = redis.get(StringToBuf(tokenNamespace+token.toString))
    maybeKey.flatMap { someKey =>
      someKey match {
        case Some(k) =>
          val key = BufToString(k)
          // if key was found delete token from key => token set
          redis.sRem(StringToChannelBuffer(apiKeyNamespace+key+":tokens"),
            List(StringToChannelBuffer(token.toString)))
          // delete token
          redis.dels(Seq(StringToBuf(tokenNamespace+token.toString)))
          return true
        case None =>
          return false
      }
    }
    // FIXME return based on above
    return true
  }

  /** Create a new key for a specific user. */
  def createApiKey(userId: String): UUID = {
    val apiKey = Key.newKey()
    // Store key => username
    redis.set(StringToBuf(apiKeyNamespace+apiKey), StringToBuf(userId))
    // Store username => keys
    redis.sAdd(StringToChannelBuffer(userNamespace+userId+":keys"), List(StringToChannelBuffer(apiKey.toString)))
    apiKey
  }

  /** Delete key and all tokens related to it. */
  def deleteApiKey(key: String): Unit = {
    // get tokens by key
    val tokens = redis.sMembers(StringToChannelBuffer(apiKeyNamespace + key + ":tokens"))
    tokens.flatMap { allTokens =>
      for (t <- allTokens) {
        log.debug("Deleting api token")
        // delete token
        val token = BytesToString(t.array())
        redis.dels(Seq(StringToBuf(tokenNamespace + token)))
      }
      return
    }
    val maybeUser = redis.get(StringToBuf(apiKeyNamespace + key))
    maybeUser.flatMap { user =>
      user match {
        case Some(userId) =>
          val id = BufToString(userId)
          // remove key from user
          redis.sRem(StringToChannelBuffer(userNamespace + id + ":keys"),
            List(StringToChannelBuffer(key)))
          // delete key
          redis.dels(Seq(StringToBuf(apiKeyNamespace + key)))
          redis.dels(Seq(StringToBuf(apiKeyNamespace + key + ":tokens")))
        case None =>
          log.error(s"Key $key not found when trying to delete it")
      }
      return
    }
  }

  @deprecated("Use getAPIKeyFuture instead", "0.1.0")
  def getAPIKey(apiKey: String): Try[String] = {
    Await.result(redis.get(StringToBuf(apiKeyNamespace+apiKey)), 5.seconds) match {
      case Some(buf) => Success(BufToString(buf))
      case None => Failure(new Exception(s"Api Key $apiKey not found"))
    }
  }

  /** Check that a key exists */
  def getAPIKeyFuture(apiKey: String): Future[Option[Buf]] = {
    redis.get(StringToBuf(apiKeyNamespace+apiKey))
  }

  /** Increase a redis counter by key */
  def increaseStat(counter: String) {
    redis.incr(StringToBuf(stats+counter))
  }

  /** Decrease a redis counter by key */
  def decreaseStat(counter: String) {
    redis.decr(StringToBuf(stats+counter))
  }

  /** Increase a stats bytes counter by number of bytes */
  def logBytes(endpoint: String, length: Int): Unit = {
    redis.incrBy(StringToBuf(stats+"bytes:"+endpoint), length)
  }

  /** Store an event when a client uploads a file or URL for conversion or extraction.
    *
    * @param eventType extraction, conversion
    * @param resource file:// for local files, http:// or https:// for remote URLs
    * @param user id of the account making the request
    * @param clientIP client IP
    */
  @deprecated("Now stored in Mongodb. See db.Mongodb.", "0.2.0")
  def storeEvent(eventType: String, resource: String, user: String, clientIP: String): Unit = {
    val millis = System.currentTimeMillis()
    val calendar = Calendar.getInstance()
    calendar.setTimeInMillis(millis)
    val id = UUID.randomUUID().toString
    val fields = Map[ChannelBuffer, ChannelBuffer](
      StringToChannelBuffer("type") -> StringToChannelBuffer(eventType),
      StringToChannelBuffer("date") -> StringToChannelBuffer(millis.toString),
      StringToChannelBuffer("resource") -> StringToChannelBuffer(resource),
      StringToChannelBuffer("user") -> StringToChannelBuffer(user),
      StringToChannelBuffer("clientIP") -> StringToChannelBuffer(clientIP)
      )
    // add event to hash
    redis.hMSet(StringToChannelBuffer(eventNamespace+id), fields)
    // add event id to sorted set ordered by date
    redis.zAdd(StringToChannelBuffer("events"), millis.toDouble, StringToChannelBuffer(eventNamespace+id))
    // quotas
    logRequestsQuota(user)

    log.debug(s"Event $eventType on $resource at ${calendar.getTime} from $clientIP")
  }

  /**
    * Decrease quota counter for user. If counter doesn't exist start one using config quotas.requests.total.
    *
    * @param userId the user ID we are updating quota for
    */
  def logRequestsQuota(userId: String): Unit = {
    log.setLevel(Level.DEBUG)
    val key = userNamespace + userId + ":requests"
    val counter = redis.get(StringToBuf(key))
    counter.map { count =>
      count match {
        case Some(k) =>
          // if key was found decrease counter
          log.debug(s"Key $key found. Decreasing counter.")
          if (BufToString(k).toInt > 0) redis.decr(StringToBuf(key))
        case None =>
          // Setting requests quota for user since it was not found
          setRequestsQuota(userId)
      }
    }
  }

  /**
    * Check if user requests quota is above zero.
    *
    * @param userId the user ID
    * @return true if user quota > 0 and false if not
    */
  def checkRequestsQuota(userId: String): Future[Boolean] = {
    val key = userNamespace + userId + ":requests"
    val counter = redis.get(StringToBuf(key))
    counter.flatMap { count =>
      count match {
        case Some(k) =>
          if (BufToString(k).toInt > 0) Future.value(true) else Future.value(false)
        case None =>
          // Setting requests quota for user since it was not found
          setRequestsQuota(userId)
          Future.value(true)
      }
    }
  }

  /**
    * Setting request quotas for user based on config file `quotas.requests.total`
    *
    * @param userId user id for which the request quota is being set
    */
  def setRequestsQuota(userId: String): Unit = {
    val key = userNamespace + userId + ":requests"
    val maxRequests = conf.getInt("quotas.requests.total")
    val initialValue = StringToBuf(maxRequests.toString)
    log.debug(s"Quotas for user $userId not found. Setting default quota $key to $maxRequests")
    redis.set(StringToBuf(key), initialValue)
  }

  /**
    * Check if user has done more than n requests over t time.
    *
    * @param userId the user ID
    * @return true if user is below rate limit, false if not
    */
  def checkRateLimit(userId: String): Future[Boolean] = {
    val key = userNamespace + userId + ":rate"
    val maxCount = conf.getInt("quotas.requests.rate.count")
    val seconds = conf.getLong("quotas.requests.rate.period")
    redis.get(StringToBuf(key)).flatMap {
      case Some(current) =>
        val cur = BufToString(current).toInt
        log.debug(s"Rate limit count: $cur / $maxCount")
        // increment key and check value
        redis.incr(StringToBuf(key)).flatMap {count =>
          if (count > maxCount) {
            Future.value(false)
          } else {
            Future.value(true)
          }
        }
      case None =>
        log.debug(s"Rate limit starting new window expiring in $seconds seconds")
        // the window has expired, we start a new one
        redis.incr(StringToBuf(key))
        redis.setEx(StringToBuf(key), seconds, StringToBuf("1"))
        Future.value(true)
    }
  }

  /**
    * Get global activity statistics.
    *
    * @return a new Stats instance
    */
  def getStats(): Future[Stats] = {
    val statsRedis = redis.mGet(Seq(
      StringToBuf(stats+"bytes:conversions"),
      StringToBuf(stats+"bytes:extractions"),
      StringToBuf(stats+"conversions"),
      StringToBuf(stats+"extractions"),
      StringToBuf(stats+"keys"),
      StringToBuf(stats+"tokens")
    ))
    statsRedis.map { s =>
      val conversionsBytes = BufToString(s(0).getOrElse(Buf.Empty)).toLong
      val extractionBytes = BufToString(s(1).getOrElse(Buf.Empty)).toLong
      val conversionsNum = BufToString(s(2).getOrElse(Buf.Empty)).toLong
      val extractionsNum = BufToString(s(3).getOrElse(Buf.Empty)).toLong
      val keysNum = BufToString(s(4).getOrElse(Buf.Empty)).toLong
      val tokensNum = BufToString(s(5).getOrElse(Buf.Empty)).toLong
      Stats(BytesStats(conversionsBytes, extractionBytes), conversionsNum, extractionsNum, keysNum, tokensNum)
    }
  }

  /**
    * Get events.
    *
    * @return a list of Events instances
    */
  @deprecated("Now stored in Mongodb. See db.Mongodb.", "0.2.0")
  def getEvents(since: Option[String], until: Option[String], limit: Long): Future[Seq[Map[String, String]]] = {
    val start = if (since.isEmpty) ZInterval("-inf") else ZInterval(since.get)
    val end = if (until.isEmpty) ZInterval("+inf") else ZInterval(until.get)
    redis.zRangeByScore(StringToChannelBuffer("events"),
      start, end, false, Some(Limit(0, limit))).flatMap {
      case Left(keys) =>
        Future.Nil
      case Right(keys) =>
        log.debug(keys.size + " events between" + start + " and " + end)
        val allFutures = keys.map(key => getEvent(key))
        Future.collect(allFutures)
    }
  }

  /**
    * Get an event by event id.
    *
    * @param eventId
    * @return the event as a Future of a Map of Strings
    */
  @deprecated("Now stored in Mongodb. See db.Mongodb.", "0.2.0")
  def getEvent(eventId: String): Future[Map[String, String]] = {
    val id = StringToChannelBuffer(eventNamespace + eventId)
    getEvent(id)
  }

  /**
    * Private method to get an event by event Id as a ChannelBuffer.
    *
    * @param eventId
    * @return events as a Future of a Map of Strings
    */
  @deprecated("Now stored in Mongodb. See db.Mongodb.", "0.2.0")
  private def getEvent(eventId: ChannelBuffer): Future[Map[String, String]] = {
    redis.hGetAll(eventId).flatMap { hash =>
      val map: Map[String, String] = hash.map(t => CBToString(t._1) -> CBToString(t._2))(collection.breakOut)
      Future.value(map)
    }
  }

  def close(): Unit = {
    log.info("Closing redis client...")
    redis.close()
  }
}
