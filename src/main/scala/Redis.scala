import java.net.{ConnectException, Socket}
import java.util.UUID

import Proxy._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Status, Response, Request}
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.util.{Await, Future}
import com.typesafe.config.ConfigFactory
import _root_.java.lang.{Long => JLong}

/**
  * Client to redis.
  */
object Redis {

  private val conf = ConfigFactory.load()

  val redis = com.twitter.finagle.redis.Client(conf.getString("redis.host")+":"+conf.getString("redis.port"))

  def newToken(key: UUID): UUID = {
    val token = Token.newToken()
    val ttl = conf.getLong("token.ttl")
    redis.setEx(StringToChannelBuffer("token:"+token.toString), ttl, StringToChannelBuffer(key.toString))
    token
  }

  def checkToken(token: UUID): Option[JLong] = {
    val result = Await.result(redis.ttl(StringToChannelBuffer("token:"+token.toString)))
    result
  }

  def close(): Unit = {
    log.info("Closing redis client...")
    redis.release()
  }
}
