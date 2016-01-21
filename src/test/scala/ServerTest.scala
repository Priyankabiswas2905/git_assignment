import java.net.InetSocketAddress

import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http.Method.Get
import com.twitter.finagle.http.Version.Http11
import com.twitter.finagle.http._
import com.twitter.util.{Await, Closable}
import edu.illinois.ncsa.fence.Server
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterEach, FunSuite}
import org.scalatest.junit.JUnitRunner

/**
  * Main test.
  */
@RunWith(classOf[JUnitRunner])
class ServerTest extends FunSuite with BeforeAndAfterEach {
  var server: com.twitter.finagle.ListeningServer = _
  var client: Service[Request, Response] = _
  override def beforeEach() {
    server = Server.start()
    client = ClientBuilder()
      .codec(Http())
      .hosts(Seq(new InetSocketAddress(8080)))
      .hostConnectionLimit(1)
      .build()
  }
  override def afterEach() {
    Closable.all(server, client).close()
  }

  test("GET Ok") {
    val request = Request(Http11, Get, "/ok")
    val responseFuture = client(request)
    val response = Await.result(responseFuture)
    assert(response.status === Status.Ok)
  }
}
