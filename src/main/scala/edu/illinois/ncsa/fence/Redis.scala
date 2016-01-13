package edu.illinois.ncsa.fence

import _root_.java.lang.{Long => JLong}
import java.util.UUID

import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.util.Await
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server._

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
    Await.result(redis.ttl(StringToChannelBuffer("token:"+token.toString)))
  }

  def close(): Unit = {
    log.info("Closing redis client...")
    redis.release()
  }
}
