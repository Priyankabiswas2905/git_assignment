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
import edu.illinois.ncsa.fence.Crowd.AuthorizeUserPassword
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}

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

  val dap: Service[Request, Response] = Http.newService(conf.getString("dap.url"))

  val dts: Service[Request, Response] = Http.newService(conf.getString("dts.url"))

  val handleExceptions = new HandleExceptions

  val authToken = new AuthorizeToken

  val crowdAuth = new AuthorizeUserPassword

//  val timeoutFilter = new TimeoutFilter[Request, Response](5.seconds, new JavaTimer())

  val authorizedDAP = authToken andThen dap

  val okStats = statsReceiver.counter("everything-is-ok")

  val dapStats = statsReceiver.counter("dap-requests")

  val dtsStats = statsReceiver.counter("dts-requests")

  val ok = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
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
    log.debug("Special upload endpoint")
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
      newReq.headerMap.set(Fields.Connection, "keep-alive")
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
    log.debug("Special upload endpoint")
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
      newReq.headerMap.set(Fields.Connection, "keep-alive")
      val rep = dap(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
    }
  }

  val cors = new Cors.HttpFilter(Cors.UnsafePermissivePolicy)

  val router = RoutingService.byMethodAndPathObject[Request] {
    case (Get, Root / "dap" / "alive") => cors andThen authToken andThen dapPath(Path("alive"))
    case (Post, "dap" /: "convert" /: path) => cors andThen authToken andThen streamingDAP("/convert/" + path)
    case (_, "dap" /: path) => cors andThen authToken andThen dapPath(path)
    case (Post, Root / "dts" / "api" / "files") => cors andThen authToken andThen streamingDTS("/api/files")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_file") => cors andThen authToken andThen streamingDTS("/api/extractions/upload_file")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_url") => cors andThen authToken andThen streamingDTS("/api/extractions/upload_url")
    case (_, "dts" /: path) => cors andThen authToken andThen dtsPath(path)
    case (Get, Root / "ok") => ok
    case (Post, Root / "keys") => cors andThen crowdAuth andThen Auth.createApiKey()
    case (Delete, Root / "keys" / key) => cors andThen crowdAuth andThen Auth.deleteApiKey(key)
    case (Post, Root / "keys" / key / "tokens") => cors andThen crowdAuth andThen Auth.newAccessToken(UUID.fromString(key))
    case (Get, Root / "tokens" / token) => cors andThen crowdAuth andThen Auth.checkToken(UUID.fromString(token))
    case (Delete, Root / "tokens" / token) => cors andThen crowdAuth andThen Auth.deleteToken(UUID.fromString(token))
    case (Get, Root / "crowd" / "session") => cors andThen Crowd.session()
    case _ => notFound
  }

  def start(): ListeningServer = {
    val server = Http.serve(":8080", router)
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
    Await.ready(server)
  }
}
