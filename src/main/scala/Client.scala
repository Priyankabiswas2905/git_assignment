import com.twitter.finagle.{Http, Service}
import com.twitter.finagle.http
import com.twitter.util.{Await, Future}
import com.twitter.logging.Logger

object Client extends App {
  private val log = Logger.get(getClass)
  val client: Service[http.Request, http.Response] = Http.newService("www.scala-lang.org:80")
  val request = http.Request(http.Method.Get, "/")
  request.host = "www.scala-lang.org"
  val response: Future[http.Response] = client(request)
  response.onSuccess { resp: http.Response =>
    log.info("GET success: " + resp)
  }
  Await.ready(response)
}