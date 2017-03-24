package edu.illinois.ncsa.fence

import java.util.UUID

import com.twitter.finagle.http._
import com.twitter.finagle.redis.util.BufToString
import com.twitter.finagle.{Service, SimpleFilter, http}
import com.twitter.io.Buf
import com.twitter.server.util.JsonConverter
import com.twitter.util.Future
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Crowd.{AuthorizeUserPassword => CrowdAuthorizeUserPassword}
import edu.illinois.ncsa.fence.Server._
import edu.illinois.ncsa.fence.auth.LocalAuthUser
import edu.illinois.ncsa.fence.util.GatewayHeaders


/**
  * Authentication and authorization methods.
  */
object Auth {

  /** Load configuration file using typesafehub config */
  private val conf = ConfigFactory.load()

  val accessTokenStats = statsReceiver.counter("fence-new-access-token")

  val checkTokenStats = statsReceiver.counter("fence-check-access-token")

  def newAccessToken(key: UUID) = new Service[http.Request, http.Response] {
    def apply(req: http.Request): Future[http.Response] = {
      Redis.getAPIKeyFuture(key.toString).flatMap { optBuf =>
        optBuf match {
          case Some(buf) => {
            val keyFromRedis = BufToString(buf)
            val token = Redis.createToken(key)
            Redis.increaseStat("tokens")
            val res = Response(req.version, Status.Ok)
            res.contentType = "application/json;charset=UTF-8"
            res.content = Buf.Utf8(JsonConverter.writeToString(Map("token"->token)))
            accessTokenStats.incr()
            Future.value(res)
          }
          case None => {
            val res = Response(req.version, Status.NotFound)
            res.contentType = "application/json;charset=UTF-8"
            res.content = Buf.Utf8(JsonConverter.writeToString(Map("error"->"API key not found")))
            Future.value(res)
          }
        }
      }
    }
  }

  def checkToken(token: UUID) = new Service[http.Request, http.Response] {
    def apply(req: http.Request): Future[http.Response] = {
      val res = Response(req.version, Status.Ok)
      res.contentType = "application/json;charset=UTF-8"

      Redis.checkToken(token) flatMap { someTtl =>
        someTtl match {
          case Some(ttl) =>
            if (ttl == -2)
              res.content = Buf.Utf8(JsonConverter.writeToString(Map("found" -> "false")))
            else
              res.content = Buf.Utf8(JsonConverter.writeToString(Map("found" -> "true", "ttl" -> ttl)))
            checkTokenStats.incr()
            Future.value(res)
          case None =>
            res.content = Buf.Utf8(JsonConverter.writeToString(Map("found" -> "false")))
            checkTokenStats.incr()
            Future.value(res)
        }
      }
    }
  }

  def deleteToken(token: UUID) = new Service[http.Request, http.Response] {
    def apply(req: http.Request): Future[http.Response] = {
      val res = Response(req.version, Status.Ok)
      res.contentType = "application/json;charset=UTF-8"
      val success = Redis.deleteToken(token)
      if (success)
        res.content = Buf.Utf8(JsonConverter.writeToString(Map("status"->"deleted")))
      else
        res.content = Buf.Utf8(JsonConverter.writeToString(Map("status"->"error")))
      Future.value(res)
    }
  }

  def createApiKey() = new Service[http.Request, http.Response] {
    def apply(req: http.Request): Future[http.Response] = {
      log.debug("[Endpoint] Create key")
      BasicAuth.extractCredentials(req) match {
        case Some(cred) =>
          val apiKey = Redis.createApiKey(cred.username)
          Redis.increaseStat("keys")
          val res = Response(req.version, Status.Ok)
          res.contentType = "application/json;charset=UTF-8"
          res.content = Buf.Utf8(JsonConverter.writeToString(Map("api-key"->apiKey)))
          Future.value(res)
        case None =>
          val res = Response(req.version, Status.NotFound)
          res.contentType = "application/json;charset=UTF-8"
          res.content = Buf.Utf8(JsonConverter.writeToString(Map("error"->"Username not found")))
          Future.value(res)
      }
    }
  }

  def deleteApiKey(key: String) = new Service[http.Request, http.Response] {
    def apply(req: http.Request): Future[http.Response] = {
      log.debug("[Endpoint] Deleting api key")
      Redis.deleteApiKey(key)
      val res = Response(req.version, Status.Ok)
      res.contentType = "application/json;charset=UTF-8"
      res.content = Buf.Utf8(JsonConverter.writeToString(Map("status"->"success")))
      Future.value(res)
    }
  }

  /**
    * Pick a provider for authenticating users. Currently local in configuration file or external in Atlassian Crowd
    * @return a simple filter checking credentials in the header of the request
    */
  def getProvider(): SimpleFilter[Request, Response] = {
    conf.getString("auth.provider") match {
      case "crowd" =>
        log.debug("Using crowd authorization")
        new CrowdAuthorizeUserPassword
      case "local" =>
        log.debug("Using local authorization")
        new LocalAuthUser
      case _ =>
        log.debug("Defaulting to crowd authorization")
        new CrowdAuthorizeUserPassword
    }
  }

  class AuthorizeToken extends SimpleFilter[Request, Response] {
    def apply(request: Request, continue: Service[Request, Response]) = {
      try {
        request.headerMap.get(Fields.Authorization) match {
          case Some(token) =>
            Redis.checkToken(UUID.fromString(token)) flatMap { someTtl =>
              someTtl match {
                case Some(ttl) =>
                  if (ttl > 0) {
                    continue(request)
                  } else {
                    invalidToken()
                  }
                case None => invalidToken()
              }
            }
          case None =>
            request.params.get("token") map { token =>
              Redis.checkToken(UUID.fromString(token)) flatMap { someTtl =>
                someTtl match {
                  case Some(ttl) =>
                    if (ttl > 0) {
                      continue(request)
                    } else {
                      invalidToken()
                    }
                  case None => invalidToken()
                }
              }
            } getOrElse invalidToken()
        }
      } catch {
        case iae: IllegalArgumentException => {
          log.error(s"Invalid token - $iae")
          invalidToken()
        }
        case _ => invalidToken()
      }
    }

    def invalidToken(): Future[Response] = {
      val errorResponse = Response(Version.Http11, Status.Forbidden)
      errorResponse.contentString = "Invalid token"
      Future(errorResponse)
    }
  }

  class TokenFilter() extends SimpleFilter[Request, Response] {
    override def apply(request: Request, continue: Service[Request, Response]): Future[Response] = {
      try {
        request.headerMap.get(Fields.Authorization) match {
          case Some(token) =>
            Redis.getUser(UUID.fromString(token)) flatMap {
                case Some(user) =>
                  request.headerMap.add(GatewayHeaders.usernameHeader, user)
                  request.headerMap.add(GatewayHeaders.tokenHeader, token)
                  continue(request)
                case None => invalidToken()
              }
          case None =>
            request.params.get("token") map { token =>
              Redis.getUser(UUID.fromString(token)) flatMap {
                case Some(user) =>
                  request.headerMap.add(GatewayHeaders.usernameHeader, user)
                  request.headerMap.add(GatewayHeaders.tokenHeader, token)
                  continue(request)
                case None => invalidToken()
              }
            } getOrElse invalidToken()
        }
      } catch {
        case iae: IllegalArgumentException => {
          log.error(s"Invalid token - $iae")
          invalidToken()
        }
        case _ => invalidToken()
      }
    }

    def invalidToken(): Future[Response] = {
      val errorResponse = Response(Version.Http11, Status.Forbidden)
      errorResponse.contentString = "Invalid token"
      Future(errorResponse)
    }
  }
}
