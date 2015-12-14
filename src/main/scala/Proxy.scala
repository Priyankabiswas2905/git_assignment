import java.net.{ConnectException, Socket}

import com.twitter.conversions.time._
import com.twitter.finagle.http._
import com.twitter.finagle.http.path._
import com.twitter.finagle.http.service.RoutingService
import com.twitter.finagle.{Http, http, Service, SimpleFilter}
import com.twitter.logging.{Formatter, Logger}
import com.twitter.server.TwitterServer
import com.twitter.util._
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}

import com.typesafe.config.ConfigFactory


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

class Authorize extends SimpleFilter[Request, Response] {
  def apply(request: Request, continue: Service[Request, Response]) = {
    if (Some("open sesame") == request.headerMap.get(Fields.Authorization)) {
      continue(request)
    } else {
      //        Future.exception(new IllegalArgumentException("You don't know the secret"))
      val errorResponse = Response(Version.Http11, Status.Forbidden)
      errorResponse.contentString = "You don't know the secret"
      Future(errorResponse)
    }
  }
}

//  val client = Http.newClient("localhost:10000,localhost:10001","cookies")
//  val proxyService = new Service[Request, Response] {
//    def apply(request: Request) = client(request)
//  }

object Proxy extends TwitterServer {

  private val conf = ConfigFactory.load()

  private[this] def assertRedisRunning() {
    try {
      new Socket("localhost", 6379)
    } catch {
      case e: ConnectException =>
        println("Error: redis must be running on port 6379")
        System.exit(1)
    }
  }

  val dap: Service[Request, Response] = Http.newService(conf.getString("dap.url"))

  val dts: Service[Request, Response] = Http.newService(conf.getString("dts.url"))

  val google: Service[Request, Response] = Http.newService("www.google.com:80")

  val handleExceptions = new HandleExceptions

  val authorize = new Authorize

  val timeoutFilter = new TimeoutFilter[HttpRequest, HttpResponse](4.nanoseconds, Timer.Nil)

  val authorizedGoogle = authorize andThen google

  val authorizedDAP = authorize andThen dap

  val okStats = statsReceiver.counter("everything-is-ok")

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
    case "dap" /: path => authorize andThen dapPath(path)
    case "dts" /: path => authorize andThen dtsPath(path)
    case Root / "ok" => ok
  }

  def main(): Unit = {
    //  assertRedisRunning()
    val server =  Http.serve(":8080", router)
    onExit { server.close() }
    Await.ready(server)
  }
}
