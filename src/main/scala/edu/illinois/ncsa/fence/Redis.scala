package edu.illinois.ncsa.fence

import _root_.java.lang.{Long => JLong}
import java.util.UUID

import com.twitter.finagle.redis.util.{BytesToString, StringToChannelBuffer}
import com.twitter.util.{Future, Await}
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server._

import scala.util.{Try, Success, Failure}

/**
  * Client to redis.
  */
object Redis {

  private val conf = ConfigFactory.load()

  private val tokenNamespace = "token:"
  private val apiKeyNamespace = "key:"
  private val userNamespace = "user:"

  val redis = com.twitter.finagle.redis.Client(conf.getString("redis.host")+":"+conf.getString("redis.port"))

  def createToken(key: UUID): UUID = {
    val token = Token.newToken()
    val ttl = conf.getLong("token.ttl")
    // token => key
    redis.setEx(StringToChannelBuffer(tokenNamespace+token.toString), ttl, StringToChannelBuffer(key.toString))
    // key => tokens
    redis.sAdd(StringToChannelBuffer(apiKeyNamespace+key+":tokens"), List(StringToChannelBuffer(token.toString)))
    token
  }

  def checkToken(token: UUID): Future[Option[JLong]] = {
    redis.ttl(StringToChannelBuffer(tokenNamespace+token.toString))
  }

  def deleteToken(token: UUID): Boolean = {
    // find key for token
    val maybeKey = redis.get(StringToChannelBuffer(tokenNamespace+token.toString))
    maybeKey.flatMap{someKey =>
      someKey match {
        case Some(k) =>
          val key = BytesToString(k.array())
          log.debug(s"Found key $key when deleting token $token")
          // if key was found delete token from key => token set
          redis.sRem(StringToChannelBuffer(apiKeyNamespace+key+":tokens"),
            List(StringToChannelBuffer(token.toString)))
          // delete token
          redis.del(Seq(StringToChannelBuffer(tokenNamespace+token.toString)))
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
    redis.set(StringToChannelBuffer(apiKeyNamespace+apiKey), StringToChannelBuffer(userId))
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
        redis.del(Seq(StringToChannelBuffer(tokenNamespace + token)))
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
          redis.del(Seq(StringToChannelBuffer(apiKeyNamespace + key)))
          redis.del(Seq(StringToChannelBuffer(apiKeyNamespace + key + ":tokens")))
        case None =>
          log.error(s"Key $key not found when trying to delete it")
      }
      return
    }
  }

  def getAPIKey(apiKey: String): Try[String] = {
    Await.result(redis.get(StringToChannelBuffer(apiKeyNamespace+apiKey))) match {
      case Some(channelBuffer) => Success(BytesToString(channelBuffer.array()))
      case None => Failure(new Exception(s"Api Key $apiKey not found"))
    }
  }

  def close(): Unit = {
    log.info("Closing redis client...")
    redis.release()
  }
}
