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
import edu.illinois.ncsa.fence.Auth.AuthorizeToken
import edu.illinois.ncsa.fence.Crowd.{AuthorizeUserPassword => CrowdAuthorizeUserPassword}
import edu.illinois.ncsa.fence.auth.LocalAuthUser
import edu.illinois.ncsa.fence.util.{Clowder, ExternalResources}

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

  val polyglot: Service[Request, Response] = Http.client.withStreaming(enabled = true).newService(conf.getString("dap.url"))

  val clowder: Service[Request, Response] = Http.client.withStreaming(enabled = true).newService(conf.getString("dts.url"))

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

  val authorizedDAP = authToken andThen polyglot

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

  def polyglotCatchAll(path: Path) = new Service[Request, Response] {
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

  def clowderCatchAll(path: Path) = new Service[Request, Response] {
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
      val rep = clowder(dtsReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
    }
  }

  def extractBytes(path: String): Service[Request, Response] = {
    log.debug("Streaming DTS upload " + path)
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
      val rep = clowder(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      log.debug("Uploaded bytes for extraction " +  req.getLength())
      Redis.increaseCounter("extractions")
      Redis.logBytes("extractions", req.getLength())
      rep
    }
  }

  def extractURL(path: String): Service[Request, Response] = {
    log.debug("Extract from URL " + path)
    Service.mk { (req: Request) =>
      // log size of file by send HEAD to file url
      val fileurl = Clowder.extractFileURL(req)
      ExternalResources.contentLengthFromHead(fileurl, "extractions")
      // create new request against Clowder
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
      val rep = clowder(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      log.debug("Uploaded bytes for extraction " +  req.getLength())
      // increment activity count
      Redis.increaseCounter("extractions")
      rep
    }
  }

  def convertBytes(path: String): Service[Request, Response] = {
    log.debug("Streaming DAP upload " + path)
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
      val rep = polyglot(newReq)
      rep.flatMap { r =>
        val hostname = conf.getString("fence.hostname")
        val dapURL = conf.getString("dap.url")
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

  def convertURL(fileType: String, encodedUrl: String): Service[Request, Response] = {
    val url = URLDecoder.decode(encodedUrl, "UTF-8")
    log.debug("Convert file from url " + encodedUrl)
    log.debug("Convert file from url " + url)
    Service.mk { (req: Request) =>
      // log size of file by send HEAD to file url
      ExternalResources.contentLengthFromHead(url, "conversions")
      val path = "/convert/" + fileType + "/" + encodedUrl
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
      val rep = polyglot(newReq)
      rep.flatMap { r =>
        val hostname = conf.getString("fence.hostname")
        val dapURL = conf.getString("dap.url")
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

  val cors = new Cors.HttpFilter(Cors.UnsafePermissivePolicy)

  val router = RoutingService.byMethodAndPathObject[Request] {
    case (Get, Root) => redirect(conf.getString("docs.root"))
    case (Get, Root / "dap" / "alive") => cors andThen authToken andThen polyglotCatchAll(Path("alive"))
    case (_, Root / "dap" / "convert" / fileType / path) =>  cors andThen authToken andThen convertURL(fileType, path)
    case (_, "dap" /: "convert" /: path) =>  cors andThen authToken andThen convertBytes("/convert" + path)
    case (_, "dap" /: path) => cors andThen authToken andThen polyglotCatchAll(path)
    case (Post, Root / "dts" / "api" / "files") => cors andThen authToken andThen extractBytes("/api/files")
    case (Post, Root / "dts" / "api" / "files" / fileId / "extractions" ) => cors andThen authToken andThen extractBytes("/api/files/" + fileId + "/extractions")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_file") => cors andThen authToken andThen extractBytes("/api/extractions/upload_file")
    case (Post, Root / "dts" / "api" / "extractions" / "upload_url") => cors andThen authToken andThen extractURL("/api/extractions/upload_url")
    case (_, "dts" /: path) => cors andThen authToken andThen clowderCatchAll(path)
    case (Get, Root / "ok") => ok
    case (Post, Root / "keys") => cors andThen userAuth andThen Auth.createApiKey()
    case (Delete, Root / "keys" / key) => cors andThen userAuth andThen Auth.deleteApiKey(key)
    case (Post, Root / "keys" / key / "tokens") => cors andThen Auth.newAccessToken(UUID.fromString(key))
    case (Get, Root / "tokens" / token) => cors andThen userAuth andThen Auth.checkToken(UUID.fromString(token))
    case (Delete, Root / "tokens" / token) => cors andThen userAuth andThen Auth.deleteToken(UUID.fromString(token))
    case (Get, Root / "crowd" / "session") => cors andThen Crowd.session()
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
