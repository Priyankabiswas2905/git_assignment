package edu.illinois.ncsa.fence

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.util.Future
import edu.illinois.ncsa.fence.util.GatewayHeaders

/**
  * Manage user quotas.
  */
object Quotas {

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
}
