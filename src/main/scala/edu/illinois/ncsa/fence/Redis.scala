package edu.illinois.ncsa.fence

import _root_.java.lang.{Long => JLong}
import java.util.{Calendar, UUID}

import com.twitter.conversions.time._
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.netty3.ChannelBufferBuf
import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.protocol.ZInterval
import com.twitter.finagle.redis.util._
import com.twitter.io.Buf
import com.twitter.util.{Await, Future}
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server._
import edu.illinois.ncsa.fence.models.{BytesStats, Event, Stats}
import org.jboss.netty.buffer.ChannelBuffer

import scala.util.{Failure, Success, Try}

/**
  * Client to redis.
  */
object Redis {

  private val conf = ConfigFactory.load()

  private val tokenNamespace = "token:"
  private val apiKeyNamespace = "key:"
  private val userNamespace = "user:"
  private val stats = "stats:"
  private val eventNamespace = "event:"
  private val host = conf.getString("redis.host")+":"+conf.getString("redis.port")

//  val redis = com.twitter.finagle.redis.Client(host)

  val redis = Client(
    ClientBuilder()
      .hosts(host)
      .hostConnectionLimit(10)
      .codec(com.twitter.finagle.redis.Redis())
      .daemon(true)
      .buildFactory())

  def createToken(key: UUID): UUID = {
    val token = Token.newToken()
    val ttl = conf.getLong("token.ttl")
    // token => key
    redis.setEx(StringToBuf(tokenNamespace+token.toString), ttl, StringToBuf(key.toString))
    // key => tokens
    redis.sAdd(StringToChannelBuffer(apiKeyNamespace+key+":tokens"), List(StringToChannelBuffer(token.toString)))
    token
  }

  def checkToken(token: UUID): Future[Option[JLong]] = {
    redis.ttl(StringToBuf(tokenNamespace+token.toString))
  }

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

  def deleteToken(token: UUID): Boolean = {
    // find key for token
    val maybeKey = redis.get(StringToBuf(tokenNamespace+token.toString))
    maybeKey.flatMap { someKey =>
      someKey match {
        case Some(k) =>
          val key = BufToString(k)
          log.debug(s"Found key $key when deleting token $token")
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

  def createApiKey(userId: String): UUID = {
    val apiKey = Key.newKey()
    // key => username
    redis.set(StringToBuf(apiKeyNamespace+apiKey), StringToBuf(userId))
    // username => keys
    redis.sAdd(StringToChannelBuffer(userNamespace+userId+":keys"), List(StringToChannelBuffer(apiKey.toString)))
    apiKey
  }

  def deleteApiKey(key: String): Unit = {
    // get tokens by key
    val tokens = redis.sMembers(StringToChannelBuffer(apiKeyNamespace + key + ":tokens"))
    tokens.flatMap { allTokens =>
      for (t <- allTokens) {
        log.debug("Deleting token " + t)
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

  @deprecated
  def getAPIKey(apiKey: String): Try[String] = {
    Await.result(redis.get(StringToBuf(apiKeyNamespace+apiKey)), 5.seconds) match {
      case Some(buf) => Success(BufToString(buf))
      case None => Failure(new Exception(s"Api Key $apiKey not found"))
    }
  }

  def getAPIKeyFuture(apiKey: String): Future[Option[Buf]] = {
    redis.get(StringToBuf(apiKeyNamespace+apiKey))
  }

  def increaseCounter(counter: String) {
    redis.incr(StringToBuf(stats+counter))
  }

  def logBytes(endpoint: String, length: Int): Unit = {
    redis.incrBy(StringToBuf(stats+"bytes:"+endpoint), length)
  }

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
    log.debug(s"Event $eventType on $resource at ${calendar.getTime} from $clientIP")
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
      val conversionsBytes = BufToString(s(0).getOrElse(Buf.Empty)).toInt
      val extractionBytes = BufToString(s(1).getOrElse(Buf.Empty)).toInt
      val conversionsNum = BufToString(s(2).getOrElse(Buf.Empty)).toInt
      val extractionsNum = BufToString(s(3).getOrElse(Buf.Empty)).toInt
      val keysNum = BufToString(s(3).getOrElse(Buf.Empty)).toInt
      val tokensNum = BufToString(s(3).getOrElse(Buf.Empty)).toInt
      Stats(BytesStats(conversionsBytes, extractionBytes), conversionsNum, extractionsNum, keysNum, tokensNum)
    }
  }

  /**
    * Get events.
    *
    * @return a list of Events instances
    */
  def getEvents(since: Option[String], until: Option[String]): Future[Seq[Map[String, String]]] = {
    //    redis.keys(StringToBuf(eventsNamespace+"*")).flatMap { keys =>
    val start = if (since.isEmpty) ZInterval("-inf") else ZInterval(since.get)
    val end = if (until.isEmpty) ZInterval("+inf") else ZInterval(until.get)
    redis.zRangeByScore(StringToChannelBuffer("events"),
      start, end, false, None).flatMap {
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
