import java.util.UUID

import Auth.{AuthorizeToken, AuthorizeUserPassword}
import com.twitter.conversions.time._
import com.twitter.finagle.http._
import com.twitter.finagle.http.path._
import com.twitter.finagle.http.service.RoutingService
import com.twitter.finagle.{Http, Service, SimpleFilter, http}
import com.twitter.server.TwitterServer
import com.twitter.util._
import com.typesafe.config.ConfigFactory
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}

class TimeoutFilter[Req, Rep](timeout: Duration, timer: Timer)
  extends SimpleFilter[Req, Rep] {
  def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {
    val res = service(request)
    res.within(timer, timeout)
  }
}

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


//  val client = Http.newClient("localhost:10000,localhost:10001","cookies")
//  val proxyService = new Service[Request, Response] {
//    def apply(request: Request) = client(request)
//  }

object Proxy extends TwitterServer {

  private val conf = ConfigFactory.load()

  val dap: Service[Request, Response] = Http.newService(conf.getString("dap.url"))

  val dts: Service[Request, Response] = Http.newService(conf.getString("dts.url"))

  val google: Service[Request, Response] = Http.newService("www.google.com:80")

  val handleExceptions = new HandleExceptions

  val authToken = new AuthorizeToken

  val authUserPass = new AuthorizeUserPassword

  val timeoutFilter = new TimeoutFilter[HttpRequest, HttpResponse](4.nanoseconds, Timer.Nil)

  val authorizedGoogle = authToken andThen google

  val authorizedDAP = authToken andThen dap

  val okStats = statsReceiver.counter("everything-is-ok")

  val dapStats = statsReceiver.counter("dap-requests")

  val dtsStats = statsReceiver.counter("dts-requests")

  val ok = new Service[http.Request, http.Response] {
    def apply(req: http.Request): Future[http.Response] = {
      val res = Response(req.version, Status.Ok)
      res.contentString = "Everything is O.K."
      okStats.incr()
      Future.value(res)
    }
  }

  def echoService(message: String) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      val rep = Response(req.version, Status.Ok)
      rep.setContentString(message)
      Future(rep)
    }
  }

  def userService(id: Int) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      val rep = Response(Version.Http11, Status.Ok)
      import scala.util.parsing.json.JSONObject
      val o = JSONObject(Map("id" -> id, "name" -> "John Smith"))
      rep.setContentTypeJson()
      rep.setContentString(o.toString)
      Future(rep)
    }
  }

  def dapPath(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      dapStats.incr()
      val dapReq = Request(req.method, path.toString)
      val user = conf.getString("dap.user")
      val password = conf.getString("dap.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      dapReq.headerMap.add(Fields.Authorization, "Basic " + encodedCredentials)
      dap(dapReq)
    }
  }

  def dtsPath(path: Path) = new Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      dtsStats.incr()
      val dtsReq = Request(req.method, path.toString)
      val user = conf.getString("dts.user")
      val password = conf.getString("dts.password")
      val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
      dtsReq.headerMap.add(Fields.Authorization, "Basic " + encodedCredentials)
      dts(dtsReq)
    }
  }

  val router = RoutingService.byPathObject[Request] {
    case Root / "user" / Integer(id) => userService(id)
    case Root / "echo" / message => echoService(message)
    case Root / "google" => authorizedGoogle
    case Root / "dap" / "alive" => dapPath(Path("alive"))
    case "dap" /: path => authToken andThen dapPath(path)
    case "dts" /: path => authToken andThen dtsPath(path)
    case Root / "ok" => ok
    case Root / "key" / key / "token" => authUserPass andThen Auth.newAccessToken(UUID.fromString(key))
    case Root / "token" / token => authUserPass andThen Auth.checkToken(UUID.fromString(token))
  }

  def main(): Unit = {
    //  assertRedisRunning()
    val server = Http.serve(":8080", router)
    onExit {
      log.info("Closing server...")
      server.close()
      Redis.close()
    }
    Await.ready(server)
  }
}
