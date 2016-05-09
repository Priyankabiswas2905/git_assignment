package edu.illinois.ncsa.fence

import java.util.UUID

import com.twitter.finagle.http._
import com.twitter.finagle.redis.util.BufToString
import com.twitter.finagle.{Service, SimpleFilter, http}
import com.twitter.io.Buf
import com.twitter.server.util.JsonConverter
import com.twitter.util.{Base64StringEncoder, Future}
import edu.illinois.ncsa.fence.Server._

import scala.util.{Failure, Success}

/**
  * Authentication and authorization methods.
  */
object Auth {

  val accessTokenStats = statsReceiver.counter("fence-new-access-token")

  val checkTokenStats = statsReceiver.counter("fence-check-access-token")

  def newAccessToken(key: UUID) = new Service[http.Request, http.Response] {
    def apply(req: http.Request): Future[http.Response] = {
      Redis.getAPIKeyFuture(key.toString).flatMap { optBuf =>
        optBuf match {
          case Some(buf) => {
            val keyFromRedis = BufToString(buf)
            val token = Redis.createToken(key)
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

//        case Future(Some(ttl)) =>
//        if (ttl == -2)
//          res.content = Buf.Utf8(JsonConverter.writeToString(Map("found" -> "false")))
//        else
//          res.content = Buf.Utf8(JsonConverter.writeToString(Map("found" -> "true", "ttl" -> ttl)))
//        case None =>
//          res.content = Buf.Utf8(JsonConverter.writeToString(Map("found" -> "false")))
//      }
//      checkTokenStats.incr()
//      Future.value(res)
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
      BasicAuth.extractCredentials(req) match {
        case Some(cred) =>
          val apiKey = Redis.createApiKey(cred.username)
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
      log.debug("Deleting api key")
      Redis.deleteApiKey(key)
      val res = Response(req.version, Status.Ok)
      res.contentType = "application/json;charset=UTF-8"
      res.content = Buf.Utf8(JsonConverter.writeToString(Map("status"->"success")))
      Future.value(res)
    }
  }

  def checkUsernamePassword(username: String, password: String): Boolean = {
    // FIXME: Look it up
    username == "open" && password == "sesame"
  }

  class AuthorizeToken extends SimpleFilter[Request, Response] {
    def apply(request: Request, continue: Service[Request, Response]) = {
      // validate token
      request.headerMap.get(Fields.Authorization) match {
        case Some(token) =>
          Redis.checkToken(UUID.fromString(token)) flatMap { someTtl =>
            someTtl match {
              case Some(ttl) =>
                if (ttl > 0) {
                  continue(request)
                } else {
                  val errorResponse = Response(Version.Http11, Status.Forbidden)
                  errorResponse.contentString = "Invalid token"
                  Future(errorResponse)
                }
              case None =>
                val errorResponse = Response(Version.Http11, Status.Forbidden)
                errorResponse.contentString = "Invalid token"
                Future(errorResponse)
            }
          }
        case None =>
          val errorResponse = Response(Version.Http11, Status.Forbidden)
          errorResponse.contentString = "Invalid token"
          Future(errorResponse)
      }
    }
  }

  class AuthorizeUserPassword extends SimpleFilter[Request, Response] {
    def apply(request: Request, continue: Service[Request, Response]) = {
      // validate credentials
      request.headerMap.get(Fields.Authorization) match {
        case Some(header) =>
          val credentials = new String(Base64StringEncoder.decode(header.substring(6))).split(":")
          if (credentials.size == 2 && checkUsernamePassword(credentials(0), credentials(1))) {
            continue(request)
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
