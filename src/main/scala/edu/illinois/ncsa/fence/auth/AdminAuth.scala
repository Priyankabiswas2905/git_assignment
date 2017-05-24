package edu.illinois.ncsa.fence.auth

import com.twitter.finagle.http._
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.Future
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.util.GatewayHeaders

/**
  * Support list of admin accounts so that certain endpoints are only accessible to users with
  * admin privileges.
  */
class AdminAuthFilter() extends SimpleFilter[Request, Response] {

  private val conf = ConfigFactory.load()

  override def apply(request: Request, continue: Service[Request, Response]): Future[Response] = {
    request.headerMap.get(GatewayHeaders.usernameHeader) match {
      case Some(user) =>
        val admins = conf.getStringList("auth.admins")
        if (admins.contains(user)) {
          continue(request)
        } else {
          val error = Response(Version.Http11, Status.Forbidden)
          error.setContentString(s"Only admins can access this endpoint.")
          Future(error)
        }
      case None =>
        val error = Response(Version.Http11, Status.InternalServerError)
        error.setContentString("User not found in request")
        Future(error)
    }
  }
}
