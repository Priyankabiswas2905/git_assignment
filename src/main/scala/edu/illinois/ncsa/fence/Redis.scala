package edu.illinois.ncsa.fence

import _root_.java.lang.{Long => JLong}
import java.util.UUID

import com.twitter.finagle.redis.util.{BytesToString, StringToChannelBuffer}
import com.twitter.util.Await
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
    redis.setEx(StringToChannelBuffer(tokenNamespace+token.toString), ttl, StringToChannelBuffer(key.toString))
    token
  }

  def checkToken(token: UUID): Option[JLong] = {
    Await.result(redis.ttl(StringToChannelBuffer(tokenNamespace+token.toString)))
  }

  def deleteToken(token: UUID): java.lang.Long = {
    Await.result(redis.del(Seq(StringToChannelBuffer(tokenNamespace+token.toString))))
  }

  def createApiKey(userId: String): UUID = {
    val apiKey = Key.newKey()
    // key -> username
    redis.set(StringToChannelBuffer(apiKeyNamespace+apiKey), StringToChannelBuffer(userId))
    // username -> keys
    redis.sAdd(StringToChannelBuffer(userNamespace+userId+":keys"), List(StringToChannelBuffer(apiKey.toString)))
    apiKey
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
