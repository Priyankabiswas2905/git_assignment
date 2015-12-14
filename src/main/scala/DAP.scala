import com.twitter.finagle.{Http, Service}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Await

object DAP extends App {
  val client: Service[Request, Response] =
    Http.newService("dap.ncsa.illinois.edu:8184")

  val server = Http.serve(":8080", client)
  Await.ready(server)
}