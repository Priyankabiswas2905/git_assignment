package edu.illinois.ncsa.fence

import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.util.{Base64StringEncoder, Future}
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server._
import edu.illinois.ncsa.fence.util.{RequestUtils, Services}

import scala.util.parsing.json.JSON

/**
  * Services to interact with RabbitMQ.
  */

object RabbitMQ {

  private val conf = ConfigFactory.load()

  // RabbitMQ connection details, see https://www.rabbitmq.com/uri-spec.html for more details.
  private val rabbitmqManagementUrl = if (conf.getString("rabbitmq.url").endsWith("/")) conf.getString("rabbitmq.url") else conf.getString("rabbitmq.url") + "/"
  private val extractorRabbitmqUsername = conf.getString("extractor.rabbitmq.username")
  private val extractorRabbitmqPassword = conf.getString("extractor.rabbitmq.password")
  private val extractorRabbitmqVHost = conf.getString("extractor.rabbitmq.vhost")

  private val converterRabbitmqUsername = conf.getString("converter.rabbitmq.username")
  private val converterRabbitmqPassword = conf.getString("converter.rabbitmq.password")
  private val converterRabbitmqVHost = conf.getString("converter.rabbitmq.vhost")

  val rabbitmqService = Services.getService("rabbitmq")

  def getQueueCount(transformationType: String) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Getting " + transformationType + " count.")

      // Return error response if query parameter is not provided
      if (transformationType == "extractions" && !req.params.contains("extractor_name")) {
        return Future.value(RequestUtils.missingParam("extractor_name"))
      }

      if (transformationType == "conversions" && !req.params.contains("software_name")) {
        return Future.value(RequestUtils.missingParam("software_name"))
      }

      // Create RabbitMQ API endpoint
      val rabbitmqApiEndpoint = transformationType match {
        case "extractions" => rabbitmqManagementUrl + "api/queues/" + extractorRabbitmqVHost + "/" + req.getParam("extractor_name")
        case "conversions" => rabbitmqManagementUrl + "api/queues/" + converterRabbitmqVHost + "/" + req.getParam("software_name")
      }
      log.debug(rabbitmqApiEndpoint)

      // Add authorization
      val encodedCredentials = transformationType match {
        case "extractions" => Base64StringEncoder.encode(s"$extractorRabbitmqUsername:$extractorRabbitmqPassword".getBytes)
        case "conversions" => Base64StringEncoder.encode(s"$converterRabbitmqUsername:$converterRabbitmqPassword".getBytes)
      }
      req.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)

      val rabbitmqRequest = Request(req.method, rabbitmqApiEndpoint)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"RabbitMQ Queues Request Header: $key -> $value")
          rabbitmqRequest.headerMap.add(key, value)
        }
      }

      val response = rabbitmqService(rabbitmqRequest)
      response.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        val jsonObject = JSON.parseFull(r.contentString)

        // Get only messages and consumers from the RabbitMQ response
        jsonObject match {
          case Some(map: Map[String, Any]) =>
            val jsonString = """{"messages":""" + map("messages") + """, "consumers":""" + map("consumers") + """}"""
            r.setContentString(jsonString)
        }

        Future.value(r)
      }
      response
    }
  }
}