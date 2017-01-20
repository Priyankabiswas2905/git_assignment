package edu.illinois.ncsa.fence

import java.net.{URL, URLDecoder}
import java.util.UUID

import com.twitter.finagle.http.Method.{Delete, Get, Post}
import com.twitter.finagle.http.Version.Http11
import com.twitter.finagle.http.{Request, _}
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
import edu.illinois.ncsa.fence.util.GatewayHeaders.GatewayHostHeaderFilter
import edu.illinois.ncsa.fence.util.{Clowder, ExternalResources, GatewayHeaders, Jackson}

import scala.util.parsing.json.JSON
import scala.util.parsing.json.JSONObject

/**
  * Main server. Read up on Twitter Server to get more info on what is included.
  */
object Server extends TwitterServer {

  /** Load configuration file using typesafehub config */
  private val conf = ConfigFactory.load()

  /** https://opensource.ncsa.illinois.edu/bitbucket/projects/POL */
  val polyglot = getService("dap")

  /** https://clowder.ncsa.illinois.edu/ */
  val clowder = getService("dts")

  /** https://opensource.ncsa.illinois.edu/bitbucket/projects/WOLF */
  val dw = getService("dw")

  /** https://opensource.ncsa.illinois.edu/bitbucket/projects/BD/repos/bd-aux-services/browse/extractor-info-fetcher */
  val extractorsInfo = getService("extractorsinfo")

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

  /** Endpoint to test if the service is running **/
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

  /** Return a url for the key prefix */
  def getServiceURL(prefixKey: String): URL = {
    val confval = conf.getString(prefixKey + ".url")
    Try(new URL(confval)).getOrElse(new URL("http://" + confval))
  }

  /** Return host:port part of the key prefix */
  def getServiceHost(prefixKey: String): String = {
    val url = getServiceURL(prefixKey)
    if (url.getPort == -1) {
      url.getHost
    } else {
      s"${url.getHost}:${url.getPort}"
    }
  }

  /** Create a service based on the key prefix, handles https */
  def getService(prefixKey: String) = {
    val url = getServiceURL(prefixKey)
    if (url.getProtocol == "https") {
      Http.client.withTls(url.getHost).withStreaming(enabled = true).newService(getServiceHost(prefixKey))
    } else {
      Http.client.withStreaming(enabled = true).newService(getServiceHost(prefixKey))
    }
  }

  /** Return the context-path of the key prefix */
  def getServiceContextPath(prefixKey: String): String = {
    getServiceURL(prefixKey).getPath
  }

  /** Create a basic auth based on the key prefix */
  def getServiceBasicAuth(prefixKey: String): String = {
    val user = conf.getString(s"$prefixKey.user")
    val password = conf.getString(s"$prefixKey.password")
    val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
    "Basic " + encodedCredentials
  }

  /** Return the URI query parameters of the original request as a String.
    * Eventually we might be more selective and remove certain parameters (such as token).
    * @param originalReq original request from which to extract the query parameters
    */
  def getURIParams(originalReq: Request): String = {
    Request.queryString(originalReq.params)
  }

  /**
    * Forward any request on to Polyglot.
    * @param path path to forward to Polyglot
    */
  def polyglotCatchAll(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Polyglot catch all")
      dapStats.incr()
      val newPathWithParameters = path + getURIParams(req)
      val dapReq = Request(req.method, newPathWithParameters)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Headers: $key -> $value")
          dapReq.headerMap.add(key, value)
        }
      }
      dapReq.headerMap.set(Fields.Host, getServiceHost("dap"))
      dapReq.headerMap.set(Fields.Authorization, getServiceBasicAuth("dap"))
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
      val newPathWithParameters = getServiceContextPath("dts") + path + getURIParams(req)
      val dtsReq = Request(req.method, newPathWithParameters)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          dtsReq.headerMap.add(key, value)
        }
      }
      dtsReq.headerMap.set(Fields.Authorization, getServiceBasicAuth("dts"))
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
      val newPathWithParameters = getServiceContextPath("dts") + path + getURIParams(req)
      val newReq = Request(Http11, Post, newPathWithParameters, req.reader)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, getServiceHost("dts"))
      newReq.headerMap.set(Fields.Authorization, getServiceBasicAuth("dts"))
      val rep = clowder(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      log.debug("Uploaded bytes for extraction " +  req.getLength())
      // log stats and events
      val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
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
      val newPathWithParameters = getServiceContextPath("dts") + path + getURIParams(req)
      val newReq = Request(Http11, Post, newPathWithParameters, req.reader)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, getServiceHost("dts"))
      newReq.headerMap.set(Fields.Authorization, getServiceBasicAuth("dts"))
      val rep = clowder(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      log.debug(s"Uploaded $req.getLength() bytes for extraction ")
      // log stats and events
      val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
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
      val newPathWithParameters = path + getURIParams(req)
      val newReq = Request(Http11, Post, newPathWithParameters, req.reader)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, getServiceHost("dap"))
      newReq.headerMap.set(Fields.Authorization, getServiceBasicAuth("dap"))
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
      val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
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
      val newPathWithParameters = "/convert/" + fileType + "/" + encodedUrl + getURIParams(req)
      val newReq = Request(Http11, Post, newPathWithParameters, req.reader)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.trace(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, getServiceHost("dap"))
      newReq.headerMap.set(Fields.Authorization, getServiceBasicAuth("dap"))
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
      val username = req.headerMap.getOrElse(GatewayHeaders.usernameHeader, "noUserFoundInHeader")
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
      val limit = req.params.getLongOrElse("limit", 1000)
      Redis.getEvents(since, until, limit).flatMap { events =>
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
      val r = Response(Http11, Status.Found)
      r.headerMap.set(Fields.Location, location)
      r.headerMap.set(Fields.CacheControl, "no-cache")
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
      val newPathWithParameters = getServiceContextPath("dw") + path + getURIParams(req)
      val newReq = Request(Http11, Post, newPathWithParameters)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          newReq.headerMap.add(key, value)
        }
      }
      val body = bodyMap.toString
      newReq.setContentString(body)
      newReq.headerMap.set(Fields.ContentLength, body.toString.length.toString)
      newReq.headerMap.set(Fields.Host, getServiceHost("dw") + getServiceContextPath("dw"))
      val rep = dw(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
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
        return Future.value(missingParam("file_type"))
      }
      val newPathWithParameters = path + getURIParams(req)
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

  /** Create a Bad Request response in cases where a query parameter is missing.
    *
    * @param param missing query parameter
    * @return a Bad Request response with message
    */
  def missingParam(param: String): Response = {
    val error = Response(Version.Http11, Status.BadRequest)
    error.setContentTypeJson()
    error.setContentString(s"""{"status":"error", "message":"Parameter '$param' required"}""")
    error
  }

  /** Swagger documentation */
  def swagger(): Service[Request, Response] = {
    log.debug("Swagger documentation")
    Service.mk { (req: Request) =>
      val r = Response()
      val text = utils.Files.readResourceFile("/swagger.json")
      r.setContentString(text)
      Future.value(r)
    }
  }

  /** Filter to authorize token */
  val tokenFilter = new TokenFilter

  /** CORS Filter */
  val cors = new Cors.HttpFilter(Cors.UnsafePermissivePolicy)

  /** Outside URL header */
  val gatewayURLFilter = new GatewayHostHeaderFilter

  /** Filter to handle exceptions. Currently not used. **/
  val handleExceptions = new HandleExceptions

  /** Common filters for all endpoints */
  val cf = cors andThen gatewayURLFilter

  /** Application router **/
  val router = RoutingService.byMethodAndPathObject[Request] {
    case (Get, Root) => redirect(conf.getString("docs.root"))
    case (Get, Root / "dap" / "alive") => cf andThen tokenFilter andThen polyglotCatchAll(Path("alive"))
    case (_, Root / "dap" / "convert" / fileType / path) =>  cf andThen tokenFilter andThen convertURL(fileType, path)
    case (_, "dap" /: "convert" /: path) =>  cf andThen tokenFilter andThen convertBytes("/convert" + path)
    case (_, "dap" /: path) => cf andThen tokenFilter andThen polyglotCatchAll(path)
    case (Post, Root / "dts" / "api" / "files") => cf andThen tokenFilter andThen extractBytes("/api/files")
    case (Post, Root / "dts" / "api" / "files" / fileId / "extractions" ) => cf andThen
      tokenFilter andThen
      extractBytes("/api/files/" + fileId + "/extractions")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_file") => cf andThen
      tokenFilter andThen
      extractBytes("/api/extractions/upload_file")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_url") => cf andThen
      tokenFilter andThen
      extractURL("/api/extractions/upload_url")
    case (_, "dts" /: path) => cf andThen tokenFilter andThen clowderCatchAll(path)
    case (Get, Root / "ok") => ok
    case (Post, Root / "keys") => cf andThen userAuth andThen Auth.createApiKey()
    case (Delete, Root / "keys" / key) => cf andThen userAuth andThen Auth.deleteApiKey(key)
    case (Post, Root / "keys" / key / "tokens") => cf andThen Auth.newAccessToken(UUID.fromString(key))
    case (Get, Root / "tokens" / token) => cf andThen userAuth andThen Auth.checkToken(UUID.fromString(token))
    case (Delete, Root / "tokens" / token) => cf andThen userAuth andThen Auth.deleteToken(UUID.fromString(token))
    case (Get, Root / "crowd" / "session") => cf andThen Crowd.session()
    case (Get, Root / "events" / eventId) => cf andThen tokenFilter andThen event(eventId)
    case (Get, Root / "events") => cf andThen tokenFilter andThen events()
    case (Get, Root / "stats") => cf andThen stats()
    case (Post, Root / "dw" / "provenance") => cf andThen tokenFilter andThen datawolfPath("/browndog/provenance")
    case (Get, Root / "extractors") => cf andThen tokenFilter andThen extractorsInfoPath("/get-extractors-info")
    case (Get, Root / "swagger.json") => cf andThen swagger
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
