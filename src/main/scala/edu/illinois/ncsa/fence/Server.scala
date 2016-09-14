package edu.illinois.ncsa.fence

import java.util.UUID

import com.twitter.conversions.time._
import com.twitter.finagle.http.Method.{Delete, Get, Post}
import com.twitter.finagle.http.Version.Http11
import com.twitter.finagle.http._
import com.twitter.finagle.http.filter.{Cors, CorsFilter}
import com.twitter.finagle.http.path.{Path, _}
import com.twitter.finagle.http.service.RoutingService
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle.{Http, ListeningServer, Service, SimpleFilter}
import com.twitter.server.TwitterServer
import com.twitter.util._
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Auth.AuthorizeToken
import edu.illinois.ncsa.fence.Crowd.{AuthorizeUserPassword => CrowdAuthorizeUserPassword}
import edu.illinois.ncsa.fence.auth.LocalAuthUser
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}
import scala.util.parsing.json.JSON

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

object Server extends TwitterServer {

  private val conf = ConfigFactory.load()

  val dap: Service[Request, Response] = Http.client.withStreaming(enabled = true).newService(conf.getString("dap.url"))

  val dts: Service[Request, Response] = Http.client.withStreaming(enabled = true).newService(conf.getString("dts.url"))
  
  val dw: Service[Request, Response] = Http.client.withStreaming(enabled = true).newService(conf.getString("dw.url"))

  val handleExceptions = new HandleExceptions

  val authToken = new AuthorizeToken

  val userAuth: SimpleFilter[Request, Response] = conf.getString("auth.provider") match {
    case "crowd" => {
      log.debug("Using crowd authorization")
      new CrowdAuthorizeUserPassword
    }
    case "local" => {
      log.debug("Using local authorization")
      new LocalAuthUser
    }
    case _ => {
      log.debug("Defaulting to crowd authorization")
      new CrowdAuthorizeUserPassword
    }
  }

//  val timeoutFilter = new TimeoutFilter[Request, Response](5.seconds, new JavaTimer())

  val authorizedDAP = authToken andThen dap

  val okStats = statsReceiver.counter("everything-is-ok")

  val dapStats = statsReceiver.counter("dap-requests")

  val dtsStats = statsReceiver.counter("dts-requests")

  val ok = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      log.debug("Called /ok")
      val res = Response(req.version, Status.Ok)
      res.contentString = "Everything is O.K."
      okStats.incr()
      Future.value(res)
    }
  }

  val notOk = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      val res = Response(req.version, Status.Ok)
      res.contentString = "Everything is NOT O.K."
      Future.value(res)
    }
  }

  val notFound = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      val res = Response(req.version, Status.NotFound)
      res.contentString = "Route not found"
      Future.value(res)
    }
  }

  def dapPath(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      dapStats.incr()
      val dapReq = Request(req.method, path.toString)
      val user = conf.getString("dap.user")
      val password = conf.getString("dap.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.debug(s"$key -> $value")
          dapReq.headerMap.add(key, value)
        }
      }
      dapReq.headerMap.set(Fields.Host, conf.getString("dap.url"))
      dapReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
      log.debug("DAP session request: " + req)
      val rep = dap(dapReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        r.headerMap.remove("Access-control-allow-credential")
        Future.value(r)
      }
      rep
    }
  }

  def dtsPath(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      dtsStats.incr()
      val dtsReq = Request(req.method, path.toString)
      val user = conf.getString("dts.user")
      val password = conf.getString("dts.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.debug(s"$key -> $value")
          dtsReq.headerMap.add(key, value)
        }
      }
      dtsReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
      log.debug("DTS: " + req)
      log.debug("DTS multipart: " + req.multipart)
      val rep = dts(dtsReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
    }
  }

  def streamingDTS(path: String): Service[Request, Response] = {
    log.debug("Streaming DTS Upload")
    Service.mk { (req: Request) =>
      val newReq = Request(Http11, Post, path, req.reader)
      val user = conf.getString("dts.user")
      val password = conf.getString("dts.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.debug(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, conf.getString("dts.url"))
      newReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
      val rep = dts(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
    }
  }

  def streamingDAP(path: String): Service[Request, Response] = {
    log.debug("Streaming DAP Upload")
    Service.mk { (req: Request) =>
      val newReq = Request(Http11, Post, path, req.reader)
      val user = conf.getString("dap.user")
      val password = conf.getString("dap.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          log.debug(s"Streaming upload header: $key -> $value")
          newReq.headerMap.add(key, value)
        }
      }
      newReq.headerMap.set(Fields.Host, conf.getString("dap.url"))
      newReq.headerMap.set(Fields.Authorization, "Basic " + encodedCredentials)
      val rep = dap(newReq)
      rep.flatMap { r =>
        val hostname = conf.getString("fence.hostname")
        val dapURL = conf.getString("dap.url")
        log.debug(s"DAP convert request ${newReq.uri} ${hostname}")
        log.debug(s"DAP convert response ${r.mediaType} ${r.contentString}")
        if (r.contentString.contains(dapURL)) {
          val body = r.contentString.replaceAll("http://" + dapURL, hostname + "/dap")
          log.debug(s"New body is $body")
          r.setContentString(body)
          r.headerMap.set(Fields.ContentLength, body.length.toString)
        }
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        r.headerMap.remove("Access-control-allow-credential")
        Future.value(r)
      }
      rep
    }
  }

  def redirect(location: String): Service[Request, Response] = {
    log.debug("Redirecting to " + location)
    Service.mk { (req: Request) =>
      val r = Response.apply(Http11, Status.MovedPermanently)
      r.headerMap.set(Fields.Location, location)
      Future.value(r)
    }
  }
  
  def datawolfPath(path: String): Service[Request, Response] = {
    log.debug("Datawolf request")
    Service.mk { (req: Request) =>
      val newReq = Request(Http11, Post, path, req.reader)
      val fenceURL = conf.getString("fence.hostname")
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          newReq.headerMap.add(key, value)
        }
      }
      val rep = dw(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
    }
  }

  val cors = new Cors.HttpFilter(Cors.UnsafePermissivePolicy)

  val router = RoutingService.byMethodAndPathObject[Request] {
    case (Get, Root) => redirect(conf.getString("docs.root"))
    case (Get, Root / "dap" / "alive") => cors andThen authToken andThen dapPath(Path("alive"))
    case (_, "dap" /: "convert" /: path) =>  cors andThen authToken andThen streamingDAP("/convert/" + path)
    case (_, "dap" /: path) => cors andThen authToken andThen dapPath(path)
    case (Post, Root / "dts" / "api" / "files") => cors andThen authToken andThen streamingDTS("/api/files")
    case (Post, Root / "dts" / "api" / "files" / fileId / "extractions" ) => cors andThen authToken andThen streamingDTS("/api/files/" + fileId + "/extractions")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_file") => cors andThen authToken andThen streamingDTS("/api/extractions/upload_file")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_url") => cors andThen authToken andThen streamingDTS("/api/extractions/upload_url")
    case (_, "dts" /: path) => cors andThen authToken andThen dtsPath(path)
    case (Get, Root / "ok") => ok
    case (Post, Root / "keys") => cors andThen userAuth andThen Auth.createApiKey()
    case (Delete, Root / "keys" / key) => cors andThen userAuth andThen Auth.deleteApiKey(key)
    case (Post, Root / "keys" / key / "tokens") => cors andThen Auth.newAccessToken(UUID.fromString(key))
    case (Get, Root / "tokens" / token) => cors andThen userAuth andThen Auth.checkToken(UUID.fromString(token))
    case (Delete, Root / "tokens" / token) => cors andThen userAuth andThen Auth.deleteToken(UUID.fromString(token))
    case (Get, Root / "crowd" / "session") => cors andThen Crowd.session()
    case (Post, Root / "dw" / "provenance") => cors andThen authToken andThen datawolfPath("/datawolf/browndog/provenance")
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
