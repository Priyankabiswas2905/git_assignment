package edu.illinois.ncsa.fence.auth

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.util.Future
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.db.Mongodb
import edu.illinois.ncsa.fence.util.{GatewayHeaders, TwitterFutures}

/**
  * Check if the user who uploaded a resource is the same as teh current user.
  */
class ResourceAuthFilter(resource: String) extends SimpleFilter[Request, Response] {

  private val conf = ConfigFactory.load()

  override def apply(request: Request, continue: Service[Request, Response]): Future[Response] = {
    request.headerMap.get(GatewayHeaders.usernameHeader) match {
      case Some(user) =>
        import TwitterFutures._
        import scala.concurrent.ExecutionContext.Implicits.global
        Mongodb.getEventsByResource(resource).asTwitter.flatMap { events =>
          if (events.exists(e => e.user == user)) {
            continue(request)
          } else {
            val error = Response(Version.Http11, Status.Forbidden)
            error.setContentString("Missing permissions to access this resource")
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
