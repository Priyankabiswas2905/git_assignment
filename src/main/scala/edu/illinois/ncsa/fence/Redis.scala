package edu.illinois.ncsa.fence

import _root_.java.lang.{Long => JLong}
import java.util.UUID

import com.twitter.finagle.redis.util.{BufToString, BytesToString, StringToBuf, StringToChannelBuffer}
import com.twitter.util.{Await, Future}
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server._
import com.twitter.conversions.time._
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.redis.{Client, Redis}
import com.twitter.io.Buf

import scala.util.{Failure, Success, Try}

/**
  * Client to redis.
  */
object Redis {

  private val conf = ConfigFactory.load()

  private val tokenNamespace = "token:"
  private val apiKeyNamespace = "key:"
  private val userNamespace = "user:"
  private val counts = "counts:"
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

  def deleteToken(token: UUID): Boolean = {
    // find key for token
    val maybeKey = redis.get(StringToBuf(tokenNamespace+token.toString))
    maybeKey.flatMap{someKey =>
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
    val maybeUser = redis.get(StringToChannelBuffer(apiKeyNamespace + key))
    maybeUser.flatMap { user =>
      user match {
        case Some(userId) =>
          val id = BytesToString(userId.array())
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
    redis.incr(StringToBuf(counts+counter))
  }

  def logBytes(endpoint: String, length: Int): Unit = {
    redis.incrBy(StringToBuf(counts+"bytes:"+endpoint), length)
  }

  def close(): Unit = {
    log.info("Closing redis client...")
    redis.close()
  }
}
