package edu.illinois.ncsa.fence

import java.net.URLDecoder
import java.util.UUID

import com.twitter.finagle.http.Method.{Delete, Get, Post}
import com.twitter.finagle.http.Version.Http11
import com.twitter.finagle.http._
import com.twitter.finagle.http.filter.Cors
import com.twitter.finagle.http.path.{Path, _}
import com.twitter.finagle.http.service.RoutingService
import com.twitter.finagle.{Http, ListeningServer, Service, SimpleFilter}
import com.twitter.server.TwitterServer
import com.twitter.util._
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Auth.TokenFilter
import edu.illinois.ncsa.fence.Crowd.{AuthorizeUserPassword => CrowdAuthorizeUserPassword}
import edu.illinois.ncsa.fence.auth.LocalAuthUser
import edu.illinois.ncsa.fence.models.Stats
import edu.illinois.ncsa.fence.util.{Clowder, ExternalResources, Jackson}
import scala.util.parsing.json.JSON
import scala.util.parsing.json.JSONObject

/**
  * Main server. Read up on Twitter Server to get more info on what is included.
  */
object Server extends TwitterServer {

  private val conf = ConfigFactory.load()

  val polyglot: Service[Request, Response] = Http.client.withStreaming(enabled = true).newService(conf.getString("dap.url"))

  val clowder: Service[Request, Response] = Http.client.withStreaming(enabled = true).newService(conf.getString("dts.url"))

  val dw: Service[Request, Response] = Http.client.withStreaming(enabled = true).newService(conf.getString("dw.url"))

  /** Filter to handle exceptions. Currently not used. **/
  val handleExceptions = new HandleExceptions

  val tokenFilter = new TokenFilter

  /** Pick a provider for authenticating users. Currently local in configuration file or external in Atlassian Crowd **/
  val userAuth: SimpleFilter[Request, Response] = conf.getString("auth.provider") match {
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

  /** Finagle based stats **/
  val okStats = statsReceiver.counter("everything-is-ok")
  val dapStats = statsReceiver.counter("dap-requests")
  val dtsStats = statsReceiver.counter("dts-requests")

  /** Endpoint to test if the services is running **/
  val ok = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] /ok")
      val res = Response(req.version, Status.Ok)
      res.contentString = "Everything is O.K."
      okStats.incr()
      Future.value(res)
    }
  }

  /** Catch all for routes not found */
  val notFound = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Route not found")
      val res = Response(req.version, Status.NotFound)
      res.contentString = "Route not found"
      Future.value(res)
    }
  }

  /**
    * Forward any request on to Polyglot.
    * @param path path to forward to Polyglot
    */
  def polyglotCatchAll(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Polyglot catch all")
      dapStats.incr()
      val dapReq = Request(req.method, path.toString)
      val user = conf.getString("dap.user")
      val password = conf.getString("dap.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Headers: $key -> $value")
          dapReq.headerMap.add(key, value)
        }
      }
      dapReq.headerMap.set(Fields.Host, conf.getString("dap.url"))
      dapReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
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
    * Forward any request on to Clowder.
    * @param path path to forward to Clowder
    */
  def clowderCatchAll(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Clowder catch all")
      dtsStats.incr()
      val dtsReq = Request(req.method, path.toString)
      val user = conf.getString("dts.user")
      val password = conf.getString("dts.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          dtsReq.headerMap.add(key, value)
        }
      }
      dtsReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
      log.debug("Clowder " + req)
      val rep = clowder(dtsReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
    }
  }

  /**
    * Extract metadata from file embedded in the body using Clowder.
    * @param path path forwarded on to Polyglot
    */
  def extractBytes(path: String): Service[Request, Response] = {
    log.debug("[Endpoint] Streaming clowder upload " + path)
    Service.mk { (req: Request) =>
      val newReq = Request(Http11, Post, path, req.reader)
      val user = conf.getString("dts.user")
      val password = conf.getString("dts.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, conf.getString("dts.url"))
      newReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
      val rep = clowder(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      log.debug("Uploaded bytes for extraction " +  req.getLength())
      // log stats and events
      val username = req.headerMap.get(Auth.usernameHeader).getOrElse("noUserFoundInHeader")
      val logKey = "extractions"
      Redis.storeEvent("extraction", "file:///", username, req.remoteSocketAddress.toString)
      Redis.logBytes(logKey, req.getLength())
      Redis.increaseCounter(logKey)
      rep
    }
  }

  /**
    * Extract metadata from file at URL using Clowder.
    * @param path path forwarded on to Clowder
    */
  def extractURL(path: String): Service[Request, Response] = {
    log.debug("[Endpoint] Extract from url " + path)
    Service.mk { (req: Request) =>
      // create new request against Clowder
      val newReq = Request(Http11, Post, path, req.reader)
      val user = conf.getString("dts.user")
      val password = conf.getString("dts.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, conf.getString("dts.url"))
      newReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
      val rep = clowder(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      log.debug(s"Uploaded $req.getLength() bytes for extraction ")
      // log stats and events
      val username = req.headerMap.get(Auth.usernameHeader).getOrElse("noUserFoundInHeader")
      val logKey = "extractions"
      val fileurl = Clowder.extractFileURL(req)
      ExternalResources.contentLengthFromHead(fileurl, logKey)
      Redis.storeEvent("extraction", fileurl, username, req.remoteSocketAddress.toString)
      Redis.logBytes(logKey, req.getLength())
      Redis.increaseCounter(logKey)
      rep
    }
  }

  /**
    * Convert file embedded in the body to a specific file type specified in the URL using Polyglot.
    * @param path path forwarded on to Polyglot
    */
  def convertBytes(path: String): Service[Request, Response] = {
    log.debug("[Endpoint] Streaming polyglot upload " + path)
    Service.mk { (req: Request) =>
      val newReq = Request(Http11, Post, path, req.reader)
      val user = conf.getString("dap.user")
      val password = conf.getString("dap.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, conf.getString("dap.url"))
      newReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
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
        Future.value(r)
      }
      log.debug("Uploaded bytes for conversion " +  req.getLength())
      // log events
      val username = req.headerMap.get(Auth.usernameHeader).getOrElse("noUserFoundInHeader")
      val logKey = "conversions"
      Redis.storeEvent("conversion", "file:///", username, req.remoteSocketAddress.toString)
      Redis.increaseCounter(logKey)
      Redis.logBytes(logKey, req.getLength())
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
      // new request
      val path = "/convert/" + fileType + "/" + encodedUrl
      val newReq = Request(Http11, Post, path, req.reader)
      val user = conf.getString("dap.user")
      val password = conf.getString("dap.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, conf.getString("dap.url"))
      newReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
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
        Future.value(r)
      }
      // log stats and events
      val username = req.headerMap.get(Auth.usernameHeader).getOrElse("noUserFoundInHeader")
      val logKey = "conversions"
      ExternalResources.contentLengthFromHead(url, logKey)
      Redis.storeEvent("conversion", url, username, req.remoteSocketAddress.toString)
      Redis.logBytes(logKey, req.getLength())
      Redis.increaseCounter(logKey)
      rep
    }
  }

  /**
    * Return global statistics about the service. See [[Stats]] for the full list.
    *
    * @return Json document including all global statistics
    */
  def stats(): Service[Request, Response] = {
    log.debug("[Endpoint] Returning statistics ")
    Service.mk { (req: Request) =>
      Redis.getStats().flatMap { statistics =>
        val json = Jackson.stringToJSON(statistics)
        val r = Response()
        r.setContentTypeJson()
        r.setContentString(json)
        Future.value(r)
      }
    }
  }

  /**
    * List all events. A time interval can be specified by providing a starting time as milliseconds from UNIX epoch.
    * The "since" parameters specifies the starting time. The "until" parameters specifies the end time.
    *
    * @return Json array of matching events.
    */
  def events(): Service[Request, Response] = {
    log.debug("[Endpoint] Get multiple events ")
    Service.mk { (req: Request) =>
      val since = req.params.get("since")
      val until = req.params.get("until")
      Redis.getEvents(since, until).flatMap { events =>
        val json = Jackson.stringToJSON(events)
        val r = Response()
        r.setContentTypeJson()
        r.setContentString(json)
        Future.value(r)
      }
    }
  }

  /**
    * Return an event by event id.
    *
    * @param eventId a string used internally to identify the event
    * @return Json document representing the specific event
    */
  def event(eventId: String): Service[Request, Response] = {
    log.debug("[Endpoint] Get event")
    Service.mk { (req: Request) =>
      Redis.getEvent(eventId).flatMap { event =>
        val json = Jackson.stringToJSON(event)
        val r = Response()
        r.setContentTypeJson()
        r.setContentString(json)
        Future.value(r)
      }
    }
  }

  def redirect(location: String): Service[Request, Response] = {
    log.debug("[Endpoint] Redirecting to " + location)
    Service.mk { (req: Request) =>
      val r = Response(Http11, Status.MovedPermanently)
      r.headerMap.set(Fields.Location, location)
      Future.value(r)
    }
  }

  def datawolfPath(path: String): Service[Request, Response] = {
    log.debug("[Endpoint] Datawolf request")
    Service.mk { (req: Request) =>
      val prevBody = JSON.parseFull(req.contentString)
      val fenceURL = conf.getString("fence.hostname")
      val bodyMap = prevBody match {
        // Add fence url to the body arguments
        case Some(e: Map[String,Any]) => JSONObject(e + ("fence" -> fenceURL))
        case _ => prevBody
      }
      val newReq = Request(Http11, Post, path)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          newReq.headerMap.add(key, value)
        }
      }
      val body = bodyMap.toString()
      newReq.setContentString(body)
      newReq.headerMap.set(Fields.ContentLength, body.toString.length.toString)
      newReq.headerMap.set(Fields.Host, conf.getString("dw.url"))
      val rep = dw(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
    }
  }

  // CORS filter
  val cors = new Cors.HttpFilter(Cors.UnsafePermissivePolicy)

  /** Application router **/
  val router = RoutingService.byMethodAndPathObject[Request] {
    case (Get, Root) => redirect(conf.getString("docs.root"))
    case (Get, Root / "dap" / "alive") => cors andThen tokenFilter andThen polyglotCatchAll(Path("alive"))
    case (_, Root / "dap" / "convert" / fileType / path) =>  cors andThen tokenFilter andThen convertURL(fileType, path)
    case (_, "dap" /: "convert" /: path) =>  cors andThen tokenFilter andThen convertBytes("/convert" + path)
    case (_, "dap" /: path) => cors andThen tokenFilter andThen polyglotCatchAll(path)
    case (Post, Root / "dts" / "api" / "files") => cors andThen tokenFilter andThen extractBytes("/api/files")
    case (Post, Root / "dts" / "api" / "files" / fileId / "extractions" ) => cors andThen
      tokenFilter andThen
      extractBytes("/api/files/" + fileId + "/extractions")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_file") => cors andThen
      tokenFilter andThen
      extractBytes("/api/extractions/upload_file")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_url") =>
      cors andThen
      tokenFilter andThen
      extractURL("/api/extractions/upload_url")
    case (_, "dts" /: path) => cors andThen tokenFilter andThen clowderCatchAll(path)
    case (Get, Root / "ok") => ok
    case (Post, Root / "keys") => cors andThen userAuth andThen Auth.createApiKey()
    case (Delete, Root / "keys" / key) => cors andThen userAuth andThen Auth.deleteApiKey(key)
    case (Post, Root / "keys" / key / "tokens") => cors andThen Auth.newAccessToken(UUID.fromString(key))
    case (Get, Root / "tokens" / token) => cors andThen userAuth andThen Auth.checkToken(UUID.fromString(token))
    case (Delete, Root / "tokens" / token) => cors andThen userAuth andThen Auth.deleteToken(UUID.fromString(token))
    case (Get, Root / "crowd" / "session") => cors andThen Crowd.session()
    case (Get, Root / "events" / eventId) => cors andThen tokenFilter andThen event(eventId)
    case (Get, Root / "events") => cors andThen tokenFilter andThen events()
    case (Get, Root / "stats") => cors andThen stats()
    case (Post, Root / "dw" / "provenance") => cors andThen tokenFilter andThen datawolfPath("/browndog/provenance")
    case _ => notFound
  }

  def start(): ListeningServer = {
    val server = Http.server.withStreaming(enabled = true).serve(":8080", router)
    onExit {
      log.info("Closing server...")
      server.close()
      Redis.close()
    }
    server
  }

  def main(): Unit = {
    log.info("Starting server...")
    val server = start()
    onExit {
      log.info("Closing server...")
      server.close()
      Redis.close()
    }
    Await.result(server)
  }
}

/**
  * Filter to properly handle exceptions. Currently not used.
  */
class HandleExceptions extends SimpleFilter[Request, Response] {
  def apply(request: Request, service: Service[Request, Response]) = {

    // `handle` asynchronously handles exceptions.
    service(request) handle { case error =>
      val statusCode = error match {
        case _: IllegalArgumentException =>
          Status.Forbidden
        case _ =>
          Status.InternalServerError
      }
      val errorResponse = Response(Version.Http11, statusCode)
      errorResponse.setContentString(error.getMessage)
      errorResponse
    }
  }
}
