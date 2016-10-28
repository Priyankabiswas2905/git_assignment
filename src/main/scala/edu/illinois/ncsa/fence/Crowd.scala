package edu.illinois.ncsa.fence

import java.util.concurrent.TimeUnit

import com.twitter.finagle.http._
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle.{Http, Service, SimpleFilter}
import com.twitter.util._
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server._
import com.twitter.conversions.time._

/**
  * Talk to Atlassian Crowd to authenticate users.
  */
object Crowd {

  private val conf = ConfigFactory.load()
  private val hostname = conf.getString("crowd.hostname")

  val crowd: Service[Request, Response] = Http.client.withTls(hostname).newService(s"$hostname:443")

  class AuthorizeUserPassword extends SimpleFilter[Request, Response] {
    def apply(request: Request, continue: Service[Request, Response]) = {
      // validate credentials
      log.trace("Validating credentials against crowd")
      request.headerMap.get(Fields.Authorization) match {
        case Some(header) =>
          val credentials = new String(Base64StringEncoder.decode(header.substring(6))).split(":")
          if (credentials.size == 2) {
//            val session = Await.result(callSessionEndpoint(credentials(0), credentials(1)))

            callSessionEndpoint(credentials(0), credentials(1)).flatMap {
              case r: Response => {
                if (r.status == Status.Ok) {
                  log.trace("Forwarding request on after checking against Crowd")
                  continue(request)
                } else {
                  log.error("Error checking credentials. Status is " + r.status)
                  log.error("Session: " + r.contentString)
                  val errorResponse = Response(Version.Http11, Status.Forbidden)
                  errorResponse.contentString = "Invalid credentials"
                  Future(errorResponse)
                }
              }
              case _ => {
                log.error("Error checking credentials. Wrong credentials " + credentials.size)
                val errorResponse = Response(Version.Http11, Status.Forbidden)
                errorResponse.contentString = "Invalid credentials"
                Future(errorResponse)
              }
            }
          } else {
            log.error("Error checking credentials. Wrong credentials " + credentials.size)
            val errorResponse = Response(Version.Http11, Status.Forbidden)
            errorResponse.contentString = "Invalid credentials"
            Future(errorResponse)
          }
        case None =>
          log.error("Error checking credentials. No header")
          val errorResponse = Response(Version.Http11, Status.Forbidden)
          errorResponse.contentString = "Invalid credentials"
          Future(errorResponse)
      }
    }
  }

  def session() = new Service[Request, Response] {
    def apply(request: Request): Future[Response] = {
      // validate credentials
      request.headerMap.get(Fields.Authorization) match {
        case Some(header) =>
          val credentials = new String(Base64StringEncoder.decode(header.substring(6))).split(":")
          if (credentials.size == 2) {
            callSessionEndpoint(credentials(0), credentials(1))
          } else {
            val errorResponse = Response(Version.Http11, Status.Forbidden)
            errorResponse.contentString = "Invalid credentials"
            Future(errorResponse)
          }
        case None =>
          val errorResponse = Response(Version.Http11, Status.Forbidden)
          errorResponse.contentString = "Invalid credentials"
          Future(errorResponse)
      }
    }
  }

  private def callSessionEndpoint(username: String, password: String): Future[Response] = {
    log.trace("Posting to /crowd/rest/usermanagement/latest/authentication?username=USERNAME")
    val crowdUsername = conf.getString("crowd.user")
    val crowdPassword = conf.getString("crowd.password")
    val req = Request(Method.Post, "/crowd/rest/usermanagement/latest/authentication?username=" + username)
    val encodedCredentials = Base64StringEncoder.encode(s"$crowdUsername:$crowdPassword".getBytes)
    req.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
    val body =
        <password><value>{password}</value></password>
    req.contentType = "application/xml"
    req.accept = "application/json"
    req.contentString = body.toString()
    crowd(req)
  }

  def sessionBackup() = new Service[Request, Response] {
    def apply(request: Request): Future[Response] = {
      // validate credentials
      request.headerMap.get(Fields.Authorization) match {
        case Some(header) =>
          val credentials = new String(Base64StringEncoder.decode(header.substring(6))).split(":")
          if (credentials.size == 2) {
            val req = Request(Method.Post, "/crowd/rest/usermanagement/latest/session")
            val user = conf.getString("crowd.user")
            val password = conf.getString("crowd.password")
            val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
            req.headerMap.add(Fields.Authorization, "Basic " + encodedCredentials)
            val body =
              <authentication-context>
                <username>{credentials(0)}</username>
                <password>{credentials(1)}</password>
                <validation-factors>
                  <validation-factor>
                    <name>remote_address</name>
                    <value>127.0.0.1</value>
                  </validation-factor>
                </validation-factors>
              </authentication-context>
            req.contentType = "application/xml"
            req.accept = "application/json"
            req.contentString = body.toString()
            Logging.debug(req)
            crowd(req) onSuccess { res =>
              Logging.debug(res)
            }
          } else {
            val errorResponse = Response(Version.Http11, Status.Forbidden)
            errorResponse.contentString = "Invalid credentials"
            Future(errorResponse)
          }
        case None =>
          val errorResponse = Response(Version.Http11, Status.Forbidden)
          errorResponse.contentString = "Invalid credentials"
          Future(errorResponse)
      }
    }
  }
}
