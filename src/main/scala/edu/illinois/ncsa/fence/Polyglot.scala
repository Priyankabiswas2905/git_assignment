package edu.illinois.ncsa.fence

import java.net.URLDecoder

import com.twitter.finagle.Service
import com.twitter.finagle.http.Method.Post
import com.twitter.finagle.http.Version.Http11
import com.twitter.finagle.http.{Fields, Request, Response}
import com.twitter.finagle.http.path.Path
import com.twitter.util.Future
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server.{log, statsReceiver}
import edu.illinois.ncsa.fence.db.Mongodb
import edu.illinois.ncsa.fence.util.{ExternalResources, GatewayHeaders, Services}

/**
  * Polyglot is a file format conversion service. For more information see
  * https://opensource.ncsa.illinois.edu/bitbucket/projects/POL.
  */
object Polyglot {

  /** Polyglot backend service */
  val polyglot = Services.getService("dap")

  // Finagle stats counter
  val polyglotStats = statsReceiver.counter("polyglot-requests")

  /** Load configuration file using typesafehub config */
  private val conf = ConfigFactory.load()

  /**
    * Forward any request on to Polyglot.
    * @param path path to forward to Polyglot
    */
  def polyglotCatchAll(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Polyglot catch all")
      polyglotStats.incr()
      val newPathWithParameters = path + Server.getURIParams(req)
      val dapReq = Request(req.method, newPathWithParameters)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Headers: $key -> $value")
          dapReq.headerMap.add(key, value)
        }
      }
      dapReq.headerMap.set(Fields.Host, Services.getServiceHost("dap"))
      dapReq.headerMap.set(Fields.Authorization, Services.getServiceBasicAuth("dap"))
      log.debug("Polyglot " + req)
      val rep = polyglot(dapReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        r.headerMap.remove("Access-control-allow-credential")
        Future.value(r)
      }
      rep
    }
  }

  /**
    * Convert file embedded in the body to a specific file type specified in the URL using Polyglot.
    * @param fileType output file type
    */
  def convertBytes(fileType: String): Service[Request, Response] = {
    log.debug("[Endpoint] Streaming polyglot upload " + fileType)
    Service.mk { (req: Request) =>
      val newPathWithParameters = fileType + "/" + Server.getURIParams(req)
      val newReq = Request(Http11, Post, newPathWithParameters, req.reader)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, Services.getServiceHost("dap"))
      newReq.headerMap.set(Fields.Authorization, Services.getServiceBasicAuth("dap"))
      newReq.headerMap.set(Fields.Accept, "text/plain")
      val rep = polyglot(newReq)
      rep.flatMap { r =>
        log.debug("Uploaded bytes for conversion " +  req.getLength())
        val hostname = conf.getString("fence.hostname")
        val dapURL = conf.getString("dap.url")
        if (r.contentString.contains(dapURL)) {
          val body = r.contentString.replaceAll("http://" + dapURL, hostname + "/dap")
          log.debug(s"New request body is $body")
          r.setContentString(body)
          r.headerMap.set(Fields.ContentLength, body.length.toString)
        }
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        r.headerMap.remove("Access-control-allow-credential")
        // log events
        val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
        val logKey = "conversions"
        val clientIP = req.headerMap.getOrElse[String]("X-Real-IP", req.remoteSocketAddress.toString)
        // extract file id
        val pattern = ((hostname + "/dap/file/").replace("/","\\/") + "(.+)").r
        r.contentString match {
          case pattern(fileId) =>
            Mongodb.addEvent("conversion", "urn:bdid:" + fileId, username, clientIP, fileId)
          case _ =>
            log.error("Error parsing file name " + r.contentString)
            Mongodb.addEvent("conversion", "error parsing file name", username, clientIP, "")
        }
        Redis.increaseStat(logKey)
        Redis.logBytes(logKey, req.getLength())
        Future.value(r)
      }
      rep
    }
  }

  /**
    * Convert file at URL to a specified file type using Polyglot.
    * @param fileType output file type
    * @param encodedUrl input URL encoded URL
    */
  def convertURL(fileType: String, encodedUrl: String): Service[Request, Response] = {
    val url = URLDecoder.decode(encodedUrl, "UTF-8")
    log.debug("[Endpoint] Convert " + url)
    Service.mk { (req: Request) =>
      val newPathWithParameters = "/convert/" + fileType + "/" + encodedUrl + Server.getURIParams(req)
      val newReq = Request(Http11, Post, newPathWithParameters, req.reader)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, Services.getServiceHost("dap"))
      newReq.headerMap.set(Fields.Authorization, Services.getServiceBasicAuth("dap"))
      val rep = polyglot(newReq)
      rep.flatMap { r =>
        val hostname = conf.getString("fence.hostname")
        val dapURL = conf.getString("dap.url")
        if (r.contentString.contains(dapURL)) {
          val body = r.contentString.replaceAll("http://" + dapURL, hostname + "/dap")
          log.debug(s"New request body is $body")
          r.setContentString(body)
          r.headerMap.set(Fields.ContentLength, body.length.toString)
        }
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        r.headerMap.remove("Access-control-allow-credential")
        // log stats and events
        val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
        val logKey = "conversions"
        ExternalResources.contentLengthFromHead(url, logKey)
        val clientIP = req.headerMap.getOrElse[String]("X-Real-IP", req.remoteSocketAddress.toString)
        // extract file id
        val pattern = ((hostname + "/dap/file/").replace("/","\\/") + "(.+)").r
        r.contentString match {
          case pattern(fileId) =>
            Mongodb.addEvent("conversion", "urn:bdid:" + fileId, username, clientIP, fileId)
          case _ =>
            log.error("Error parsing file name: " + r.contentString)
            Mongodb.addEvent("conversion", "Error parsing file name: " + r.contentString, username, clientIP, "")
        }
        Redis.logBytes(logKey, req.getLength())
        Redis.increaseStat(logKey)
        Future.value(r)
      }
      rep
    }
  }
}
