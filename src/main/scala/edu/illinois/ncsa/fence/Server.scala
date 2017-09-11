package edu.illinois.ncsa.fence

import java.util.UUID

import com.twitter.finagle.http.Method.{Delete, Get, Options, Post}
import com.twitter.finagle.http.Version.Http11
import com.twitter.finagle.http.filter.Cors
import com.twitter.finagle.http.path.{Path, _}
import com.twitter.finagle.http.service.RoutingService
import com.twitter.finagle.http.{Request, _}
import com.twitter.finagle.{Http, ListeningServer, Service, SimpleFilter}
import com.twitter.server.TwitterServer
import com.twitter.util.{Await, Future}
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Auth.TokenFilter
import edu.illinois.ncsa.fence.Quotas.{RateLimitFilter, RequestsQuotasFilter}
import edu.illinois.ncsa.fence.auth.{AdminAuthFilter, ResourceAuthFilter}
import edu.illinois.ncsa.fence.db.Mongodb
import edu.illinois.ncsa.fence.util.GatewayHeaders.GatewayHostHeaderFilter
import edu.illinois.ncsa.fence.util._

/**
  * Main server. Includes the router and the main method to start up the server.
  */
object Server extends TwitterServer {

  /** Load configuration file using typesafehub config */
  private val conf = ConfigFactory.load()

  /** Endpoint to test if the service is running **/
  val ok = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] /ok")
      val res = Response(req.version, Status.Ok)
      res.contentString = "Everything is O.K."
      // Finagle based stats
      val okStats = statsReceiver.counter("everything-is-ok")
      okStats.incr()
      Future.value(res)
    }
  }

  /** Catch all for routes not found */
  val notFound = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("[Endpoint] Route not found: " +  req.path)
      val res = Response(req.version, Status.NotFound)
      res.contentString = "Route not found"
      Future.value(res)
    }
  }

  /**
    * Return supported HTTP methods for a specific OPTIONS request.
    * @param strings list of supported methods
    * @return a service
    */
  def options(strings: Method*): Service[Request, Response] = {
    log.debug("Support HTTP OPTIONS: " + strings.mkString(", "))
    Service.mk { (req: Request) =>
      val r = Response()
      r.setContentTypeJson()
      r.headerMap.add("Access-Control-Allow-Methods", strings.mkString(", "))
      Future.value(r)
    }
  }

  /**
    * Serve static file available in classpath.
    *
    * @param path file path in classpath
    * @return a service
    */
  def staticFile(path: String): Service[Request, Response] = {
    log.debug("[Endpoint] Serving static file " + path)
    Service.mk { (req: Request) =>
      val r = Response()
      r.setContentTypeJson()
      val text = Files.readResourceFile(path)
      r.setContentString(text)
      Future.value(r)
    }
  }

  /** Redirect to a different location.
    * @param location
    * @return a service
    */
  def redirect(location: String): Service[Request, Response] = {
    log.debug("[Endpoint] Redirecting to " + location)
    Service.mk { (req: Request) =>
      val r = Response(Http11, Status.Found)
      r.headerMap.set(Fields.Location, location)
      r.headerMap.set(Fields.CacheControl, "no-cache")
      Future.value(r)
    }
  }

  /** Return the URI query parameters of the original request as a String.
    * Eventually we might be more selective and remove certain parameters (such as token).
    * @param originalReq original request from which to extract the query parameters
    */
  def getURIParams(originalReq: Request): String = {
    Request.queryString(originalReq.params)
  }

  /** Filter to authorize token */
  val tokenFilter = new TokenFilter

  /** CORS Filter */
  val cors = new Cors.HttpFilter(Cors.UnsafePermissivePolicy)

  /** Outside URL header */
  val gatewayURLFilter = new GatewayHostHeaderFilter

  /** Filter to handle exceptions. Currently not used. **/
  val handleExceptions = new HandleExceptions

  /** Filter to let only users who are in the admin list **/
  val admin = new AdminAuthFilter

  /** Filter to handle user quotas */
  val checkQuotas = new RequestsQuotasFilter

  /** Filter to handle rate limiting */
  val rateLimit = new RateLimitFilter

  /** Aggregate filter to rate limit and check quotas **/
  val quotas = rateLimit andThen checkQuotas

  /** Common filters for all endpoints */
  val cf = cors andThen gatewayURLFilter

  /** User authentication **/
  val userAuth = Auth.getProvider()

  /** Application router **/
  val router = RoutingService.byMethodAndPathObject[Request] {

    case (Get, Root / "ok") => ok

    case (Get, Root) =>
      redirect(conf.getString("docs.root"))

    case (Get, Root / "swagger.json") =>
      cf andThen staticFile("/swagger.json")

    case (Get, Root / "swagger.yaml") =>
      cf andThen staticFile("/swagger.yaml")

    // Conversion endpoints
    case (Get | Options, Root / "polyglot" / "alive") =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(Path("alive"))

    // TODO return JSON
    case (Get | Options, Root / "conversions" / "outputs" / format ) =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(Path("/outputs/" + format))

    // TODO return JSON
    case (Get | Options, Root / "conversions" / "outputs" ) =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(Path("/outputs"))

    // TODO return JSON
    case (Get | Options, Root / "conversions" / "inputs" / format ) =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(Path("/inputs/" + format))

    // TODO return JSON
    case (Get | Options, Root / "conversions" / "inputs" ) =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(Path("/outputs"))

    case (Get | Options, Root / "conversions" / "file" / fileId) =>
      cf andThen tokenFilter andThen rateLimit andThen new ResourceAuthFilter(fileId) andThen
        Polyglot.polyglotCatchAll(Path("/file/" + fileId))

    case (Get | Options, Root / "conversions" / "path" / output / input) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.polyglotCatchAll(Path("/path/" + output + "/" + input))

    case (Get | Options, Root / "conversions" / "software") =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.polyglotCatchAll(Path("/software/"))

    case (Get | Options, Root / "conversions" / "software" / software) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.polyglotCatchAll(Path("/software/" + software))

    case (Get | Options, Root / "conversions" / "software" / software / outputFormat) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.polyglotCatchAll(Path("/software/" + software + "/" + outputFormat))

    case (Get | Options, Root / "conversions" / "servers") =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.polyglotCatchAll(Path("/servers/"))

    case (Get | Options, Root / "conversions" / fileType / path) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.convertURL(fileType, path)

    case (Get | Options, Root / "conversions" / "path" / outputFormat) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.polyglotCatchAll(Path("/convert"))

    case (Post | Options, "conversions" /: format) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.convertBytes("/convert" + format)

    case (Get | Options, Root / "conversions") =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.polyglotCatchAll(Path("/convert"))

    case (_, "polyglot" /: path) =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(path)

    // Deprecated DAP (Polyglot) endpoints
    case (Get, Root / "dap" / "alive") =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(Path("alive"))

    case (Get | Options, Root / "dap" / "file" / fileId) =>
      cf andThen tokenFilter andThen rateLimit andThen Polyglot.polyglotCatchAll(Path("/file/" + fileId))

    case (_, Root / "dap" / "convert" / fileType / path) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.convertURL(fileType, path)

    case (_, "dap" /: "convert" /: path) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.convertBytes("/convert" + path)

    case (_, "dap" /: path) =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(path)

    // Extraction endpoints
    case (Get | Options, Root / "extractions" / fileId / "status") =>
      cf andThen tokenFilter andThen new ResourceAuthFilter(fileId) andThen
        Clowder.clowderCatchAll(Path("/api/extractions/" + fileId + "/status"))

    case (Get | Options, Root / "extractions" / "files" / fileId) =>
      cf andThen tokenFilter andThen new ResourceAuthFilter(fileId) andThen
        Clowder.clowderCatchAll(Path("/api/files/" + fileId + "/metadata"))

    case (Post | Options, Root / "extractions" / "files" / fileId) =>
      cf andThen tokenFilter andThen quotas andThen new ResourceAuthFilter(fileId) andThen
        Clowder.extractBytes("/api/files/" + fileId + "/extractions")

    case (Delete, Root / "extractions" / "files" / fileId) =>
      cf andThen tokenFilter andThen quotas andThen new ResourceAuthFilter(fileId) andThen
        Clowder.clowderCatchAll(Path("/api/files/" + fileId))

    case (_, Root / "extractions" / "files" / fileId / "metadata.jsonld" ) =>
      cf andThen tokenFilter andThen quotas andThen new ResourceAuthFilter(fileId) andThen
        Clowder.clowderCatchAll(Path("/api/files/" + fileId + "/metadata.jsonld"))

    case (Post, Root / "extractions" / "file") =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractBytes("/api/extractions/upload_file")

    case (Post, Root / "extractions" / "url") =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractURL("/api/extractions/upload_url")

    case (Get | Options, Root / "extractors" / "details") =>
      cf andThen tokenFilter andThen Clowder.clowderCatchAll(Path("/api/extractors"))

    case (Get | Options, Root / "extractors" / "details") =>
      cf andThen tokenFilter andThen Clowder.clowderCatchAll(Path("/api/extractors"))

    case (Get | Options, Root / "extractors" / "running") =>
      cf andThen tokenFilter andThen Clowder.clowderCatchAll(Path("/api/extractions/extractors_names"))

    case (Get | Options, Root / "extractors" / "running" / "servers") =>
      cf andThen tokenFilter andThen admin andThen Clowder.clowderCatchAll(Path("/api/extractions/servers_ips"))

    case (Get | Options, Root / "extractors" / "instances") =>
      cf andThen tokenFilter andThen admin andThen Clowder.clowderCatchAll(Path("/api/extractions/extractors_details"))

    case (Get | Options, Root / "extractors" / fileId / "status") =>
      cf andThen tokenFilter andThen new ResourceAuthFilter(fileId) andThen
        Clowder.clowderCatchAll(Path("/api/extractions/" + fileId + "/status"))

    case (_, Root / "extractors") =>
      cf andThen tokenFilter andThen Clowder.extractorsInfoPath("/get-extractors-info")

    case (_, "extractions" /: path) =>
      cf andThen tokenFilter andThen Clowder.clowderCatchAll(path)

    // Deprecated DTS (Clowder) endpoints
    case (Post, Root / "dts" / "api" / "files") =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractBytes("/api/files")

    case (Post, Root / "dts" / "api" / "files" / fileId / "extractions" ) =>
      cf andThen tokenFilter andThen quotas andThen new ResourceAuthFilter(fileId) andThen
        Clowder.extractBytes("/api/files/" + fileId + "/extractions")

    case (Post, Root / "dts" / "api" / "extractions" / "upload_file") =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractBytes("/api/extractions/upload_file")

    case (Post, Root / "dts" / "api" / "extractions" / "upload_url") =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractURL("/api/extractions/upload_url")

    case (_, "dts" /: path) =>
      cf andThen tokenFilter andThen Clowder.clowderCatchAll(path)

    // Datawolf endpoint
    case (_, Root / "provenance") =>
      cf andThen tokenFilter andThen Datawolf.datawolfPath("/browndog/provenance")

    // Deprecated Datawolf endpoint
    case (_, Root / "dw" / "provenance") =>
      cf andThen tokenFilter andThen Datawolf.datawolfPath("/browndog/provenance")

    case (_, "dw" /: path) => cf andThen tokenFilter andThen Datawolf.datawolfCatchAll(path)

    // Keys and Tokens
    case (Options, Root / "keys") =>
      cf andThen options(Post)

    case (Post, Root / "keys") =>
      cf andThen userAuth andThen Auth.createApiKey()

    case (Options, Root / "keys" / key) =>
      cf andThen options(Delete)

    case (Delete, Root / "keys" / key) =>
      cf andThen userAuth andThen Auth.deleteApiKey(key)

    case (Options, Root / "keys" / key / "tokens") =>
      cf andThen options(Post)

    case (Post, Root / "keys" / key / "tokens") =>
      cf andThen Auth.newAccessToken(UUID.fromString(key))

    case (Options, Root / "tokens" / token) =>
      cf andThen options(Get, Delete)

    case (Get, Root / "tokens" / token) =>
      cf andThen Auth.checkToken(UUID.fromString(token))

    case (Delete, Root / "tokens" / token) =>
      cf andThen userAuth andThen Auth.deleteToken(UUID.fromString(token))

    case (Get, Root / "crowd" / "session") =>
      cf andThen Crowd.session()

    // Events and Stats
    case (Get | Options, Root / "events" / "latest") =>
      cf andThen tokenFilter andThen admin andThen Events.latestEvents()

    case (Get | Options, Root / "events" / eventId) =>
      cf andThen tokenFilter andThen admin andThen Events.event(eventId)

    case (Get | Options, Root / "events") =>
      cf andThen tokenFilter andThen admin andThen Events.events()

    case (Get | Options, Root / "stats") =>
      cf andThen tokenFilter andThen admin andThen Events.stats()

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
    log.info("Setting up Mongodb indexes...")
    Mongodb.setupIndexes()
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

