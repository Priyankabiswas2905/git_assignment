package edu.illinois.ncsa.fence.auth

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http._
import com.twitter.util.{Base64StringEncoder, Future}
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server._

/**
  * Local authentication provider. Looks up users defined in configuration file.
  */
class LocalAuthUser extends SimpleFilter[Request, Response] {

  private val conf = ConfigFactory.load()

  def apply(request: Request, continue: Service[Request, Response]) = {
    // validate credentials
    log.trace("Validating credentials against local provider")
    request.headerMap.get(Fields.Authorization) match {
      case Some(header) =>
        val credentials = new String(Base64StringEncoder.decode(header.substring(6))).split(":")
        if (credentials.size == 2) {
          import scala.collection.JavaConversions._
          val accounts = conf.getConfigList("auth.local.users")
          val authenticated = accounts.exists(c =>
            c.getString("username").equals(credentials(0)) && c.getString("password").equals(credentials(1))
          )
          if (authenticated) {
            continue(request)
          } else {
            log.error("Error checking credentials. No user found ")
            invalidCredentials
          }
        } else {
          log.error("Error checking credentials. Wrong credentials " + credentials.size)
          invalidCredentials
        }
      case None =>
        log.error("Error checking credentials. No header")
        invalidCredentials
    }
  }

  def invalidCredentials = {
    val errorResponse = Response(Version.Http11, Status.Forbidden)
    errorResponse.contentString = "Invalid credentials"
    Future(errorResponse)
  }
}
