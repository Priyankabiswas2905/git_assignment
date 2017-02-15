package edu.illinois.ncsa.fence.util

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.Future
import com.typesafe.config.ConfigFactory

/**
  * Store and manipulate Gateway headers.
  */
object GatewayHeaders {

  /** Load configuration file using typesafehub config */
  private val conf = ConfigFactory.load()

  // TODO Change to BD-Username
  val usernameHeader = "X-BD-Username"

  val gatewayHostHeader = "BD-Host"

  val tokenHeader = "BD-Access-Token"

  class GatewayHostHeaderFilter() extends SimpleFilter[Request, Response] {
    override def apply(request: Request, continue: Service[Request, Response]): Future[Response] = {
      conf.getString("auth.provider")
      request.headerMap.add(gatewayHostHeader, conf.getString("fence.hostname"))
      continue(request)
    }
  }
}
