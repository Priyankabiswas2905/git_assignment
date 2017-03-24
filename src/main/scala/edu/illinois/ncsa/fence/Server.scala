package edu.illinois.ncsa.fence

import java.util.UUID

import com.twitter.finagle.http.Method.{Delete, Get, Post}
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
      log.debug("[Endpoint] Route not found")
      val res = Response(req.version, Status.NotFound)
      res.contentString = "Route not found"
      Future.value(res)
    }
  }

  /** Swagger documentation */
  def swagger(): Service[Request, Response] = {
    log.debug("Swagger documentation")
    Service.mk { (req: Request) =>
      val r = Response()
      val text = Files.readResourceFile("/swagger.json")
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

    case (Get, Root / "rate") =>
      tokenFilter andThen rateLimit andThen ok

    case (Get, Root) =>
      redirect(conf.getString("docs.root"))

    case (Get, Root / "dap" / "alive") =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(Path("alive"))

    case (_, Root / "dap" / "convert" / fileType / path) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.convertURL(fileType, path)

    case (_, "dap" /: "convert" /: path) =>
      cf andThen tokenFilter andThen quotas andThen Polyglot.convertBytes("/convert" + path)

    case (_, "dap" /: path) =>
      cf andThen tokenFilter andThen Polyglot.polyglotCatchAll(path)

    case (Post, Root / "dts" / "api" / "files") =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractBytes("/api/files")

    case (Post, Root / "dts" / "api" / "files" / fileId / "extractions" ) =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractBytes("/api/files/" + fileId + "/extractions")

    case (Post, Root / "dts" / "api" / "extractions" / "upload_file") =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractBytes("/api/extractions/upload_file")

    case (Post, Root / "dts" / "api" / "extractions" / "upload_url") =>
      cf andThen tokenFilter andThen quotas andThen Clowder.extractURL("/api/extractions/upload_url")

    case (_, "dts" /: path) =>
      cf andThen tokenFilter andThen Clowder.clowderCatchAll(path)

    case (Get, Root / "ok") => ok

    case (Post, Root / "keys") =>
      cf andThen userAuth andThen Auth.createApiKey()

    case (Delete, Root / "keys" / key) =>
      cf andThen userAuth andThen Auth.deleteApiKey(key)

    case (Post, Root / "keys" / key / "tokens") =>
      cf andThen Auth.newAccessToken(UUID.fromString(key))

    case (Get, Root / "tokens" / token) =>
      cf andThen userAuth andThen Auth.checkToken(UUID.fromString(token))

    case (Delete, Root / "tokens" / token) =>
      cf andThen userAuth andThen Auth.deleteToken(UUID.fromString(token))

    case (Get, Root / "crowd" / "session") =>
      cf andThen Crowd.session()

    case (Get, Root / "events" / "latest") =>
      cf andThen tokenFilter andThen Events.latestEvents()

    case (Get, Root / "events" / eventId) =>
      cf andThen tokenFilter andThen Events.event(eventId)

    case (Get, Root / "events") =>
      cf andThen tokenFilter andThen Events.events()

    case (Get, Root / "stats") =>
      cf andThen Events.stats()

    case (_, Root / "dw" / "provenance") =>
      cf andThen tokenFilter andThen Datawolf.datawolfPath("/browndog/provenance")

    case (_, Root / "extractors") =>
      cf andThen tokenFilter andThen Clowder.extractorsInfoPath("/get-extractors-info")

    case (Get, Root / "swagger.json") =>
      cf andThen swagger

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

