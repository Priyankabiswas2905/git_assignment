package edu.illinois.ncsa.fence

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.util.Future
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.util.GatewayHeaders

/**
  * Manage user quotas.
  */
object Quotas {

  private val conf = ConfigFactory.load()

  class RequestsQuotasFilter() extends SimpleFilter[Request, Response] {
    override def apply(request: Request, continue: Service[Request, Response]): Future[Response] = {
      request.headerMap.get(GatewayHeaders.usernameHeader) match {
        case Some(user) =>
          Redis.checkRequestsQuota(user) flatMap { valid =>
            if (valid) {
              continue(request)
            } else {
              val error = Response(Version.Http11, Status.Forbidden)
              error.setContentString("Requests quotas depleted")
              Future(error)
            }
          }
        case None =>
          val error = Response(Version.Http11, Status.InternalServerError)
          error.setContentString("User not found in request")
          Future(error)
      }
    }
  }

  class RateLimitFilter() extends SimpleFilter[Request, Response] {
    override def apply(request: Request, continue: Service[Request, Response]): Future[Response] = {
      request.headerMap.get(GatewayHeaders.usernameHeader) match {
        case Some(user) =>
          Redis.checkRateLimit(user) flatMap { valid =>
            if (valid) {
              continue(request)
            } else {
              val seconds = conf.getLong("quotas.requests.rate.period")
              val error = Response(Version.Http11, Status.TooManyRequests)
              error.setContentString(s"Too many requests. Try again in $seconds seconds.")
              Future(error)
            }
          }
        case None =>
          val error = Response(Version.Http11, Status.InternalServerError)
          error.setContentString("User not found in request")
          Future(error)
      }
    }
  }
}
