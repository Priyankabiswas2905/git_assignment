package edu.illinois.ncsa.fence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.Service
import com.twitter.finagle.http.Method.Post
import com.twitter.finagle.http.Version.Http11
import com.twitter.finagle.http._
import com.twitter.finagle.http.path.Path
import com.twitter.finagle.redis.util.BufToString
import com.twitter.util.Future
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server.{log, statsReceiver}
import edu.illinois.ncsa.fence.db.Mongodb
import edu.illinois.ncsa.fence.util.{ExternalResources, GatewayHeaders, RequestUtils, Services}

/**
  * Services to interact with Clowder. Clowder is a data managements system for research data. Fro more information see
  * https://clowder.ncsa.illinois.edu/. The standalone extractors information fetcher service is also used to list what
  * extractors are online for a specific file type. For more information on the extraction information service see
  * https://opensource.ncsa.illinois.edu/bitbucket/projects/BD/repos/bd-aux-services/browse/extractor-info-fetcher.
  */
object Clowder {

  /** Clowder backend service */
  val clowder = Services.getService("dts")

  /** Extraction information fetcher backend service */
  val extractorsInfo = Services.getService("extractorsinfo")

  // Finagle stats counter
  val clowderStats = statsReceiver.counter("clowder-requests")

  // Jackson JSON parsing
  val mapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)

  /** Load configuration file using typesafehub config */
  private val conf = ConfigFactory.load()

  /**
    * Forward any request on to Clowder.
    * @param path path to forward to Clowder
    */
  def clowderCatchAll(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Clowder catch all")

      val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
      Redis.getClowderKeyFuture(username) flatMap {
        case Some(userkey) => {
          clowderStats.incr()
          val newPathWithParameters = Services.getServiceContextPath("dts") + path +
            "?key=" + BufToString(userkey) + Server.getURIParams(req).replace("?", "&")
          val dtsReq = Request(req.method, newPathWithParameters)
          req.headerMap.keys.foreach { key =>
            req.headerMap.get(key).foreach { value =>
              log.trace(s"Streaming upload header: $key -> $value")
              dtsReq.headerMap.add(key, value)
            }
          }
          log.debug("Clowder " + dtsReq)
          val rep = clowder(dtsReq)
          rep.flatMap { r =>
            r.headerMap.remove(Fields.AccessControlAllowOrigin)
            r.headerMap.remove(Fields.AccessControlAllowCredentials)
            Future.value(r)
          }
          rep
        }
        case None => {
          val errorResponse = Response(Version.Http11, Status.Forbidden)
          errorResponse.contentString = "Invalid clowder Key, please try to get key."
          Future(errorResponse)
        }
  }

  /**
    * Extract metadata from file embedded in the body using Clowder.
 *
    * @param path path forwarded on to Polyglot
    */
  def extractBytes(path: String): Service[Request, Response] = {
    log.debug("[Endpoint] Streaming clowder upload " + path)
    Service.mk { (req: Request) =>

      val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
      Redis.getClowderKeyFuture(username) flatMap {
        case Some(userkey) => {

          val newPathWithParameters = Services.getServiceContextPath("dts") + path +
            "?key=" + BufToString(userkey) + Server.getURIParams(req).replace("?", "&")
          log.debug("[Endpoint] Streaming clowder upload " + newPathWithParameters)

          val newReq = Request(Http11, Post, newPathWithParameters, req.reader)
          req.headerMap.keys.foreach { key =>
            req.headerMap.get(key).foreach { value =>
              log.trace(s"Streaming upload header: $key -> $value")
              newReq.headerMap.add(key, value)
            }
          }
          newReq.headerMap.set(Fields.Host, Services.getServiceHost("dts"))

          val rep = clowder(newReq)
          rep.flatMap { r =>
            r.headerMap.remove(Fields.AccessControlAllowOrigin)
            r.headerMap.remove(Fields.AccessControlAllowCredentials)
            log.debug("Uploaded bytes for extraction " + req.getLength())
            // log stats and events
            val logKey = "extractions"
            val clientIP = req.headerMap.getOrElse[String]("X-Real-IP", req.remoteSocketAddress.toString)
            val id = extractId(r)
            Mongodb.addEvent("extraction", "urn:bdid:" + id, username, clientIP, id)
            Redis.logBytes(logKey, req.getLength())
            Redis.increaseStat(logKey)
            Future.value(r)
          }
          rep
        }
        case None => {
          val errorResponse = Response(Version.Http11, Status.Forbidden)
          errorResponse.contentString = "Invalid clowder Key, please try to get key."
          Future(errorResponse)
        }
      }
    }
  }

  /**
    * Extract metadata from file at URL using Clowder.
    * @param path path forwarded on to Clowder
    */
  def extractURL(path: String): Service[Request, Response] = {
    log.debug("[Endpoint] Extract from url " + path)
    Service.mk { (req: Request) =>
      val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
      Redis.getClowderKeyFuture(username) flatMap {
        case Some(userkey) => {
          val newPathWithParameters = Services.getServiceContextPath("dts") + path +
          "?key=" + BufToString(userkey) + Server.getURIParams(req).replace("?", "&")
          val newReq = Request(Http11, Post, newPathWithParameters, req.reader)
          req.headerMap.keys.foreach { key =>
            req.headerMap.get(key).foreach { value =>
              log.trace(s"Streaming upload header: $key -> $value")
              newReq.headerMap.add(key, value)
            }
          }
          newReq.headerMap.set(Fields.Host, Services.getServiceHost("dts"))
          newReq.headerMap.set(Fields.ContentType, "application/json")
          val rep = clowder(newReq)
          rep.flatMap { r =>
            r.headerMap.remove(Fields.AccessControlAllowOrigin)
            r.headerMap.remove(Fields.AccessControlAllowCredentials)
            log.debug(s"Uploaded $req.getLength() bytes for extraction ")
            // log stats and events
            val logKey = "extractions"
            val fileurl = Clowder.extractFileURL(req)
            ExternalResources.contentLengthFromHead(fileurl, logKey)
            val clientIP = req.headerMap.getOrElse[String]("X-Real-IP", req.remoteSocketAddress.toString)
            val id = extractId(r)
            Mongodb.addEvent("extraction", fileurl, username, clientIP, id)
            Redis.logBytes(logKey, req.getLength())
            Redis.increaseStat(logKey)
            Future.value(r)
          }
          rep
        }
          case None => {
            val errorResponse = Response(Version.Http11, Status.Forbidden)
            errorResponse.contentString = "Invalid clowder Key, please try to get key."
            Future(errorResponse)
          }
        }
      }
    }


  /**
    * Proxy to Extractors Info service.
    *
    * @param path path fragment
    */
  def extractorsInfoPath(path: String) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Extractors info")
      // return error response if query parameter is not provided
      if (!req.params.contains("file_type")) {
        return Future.value(RequestUtils.missingParam("file_type"))
      }
      val newPathWithParameters = path + Server.getURIParams(req)
      val eiReq = Request(req.method, newPathWithParameters)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Extractors Info Header: $key -> $value")
          eiReq.headerMap.add(key, value)
        }
      }
      log.debug("Extractors Info " + req)
      val rep = extractorsInfo(eiReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
    }
  }

  private def extractId(res: Response): String = {
    val json = mapper.readTree(res.getContentString())
    json.findValue("id").asText()
  }

  /**
    * Extract file url from the body of the request.
    * @param req request
    * @return file url
    */
  private def extractFileURL(req: Request): String = {
    val json = mapper.readTree(req.getContentString())
    val fileurl = json.findValue("fileurl").asText()
    log.debug("Extracted fileurl from body of request: " + fileurl)
    fileurl
  }
}
