package edu.illinois.ncsa.fence.exp

import javax.inject.{Inject, Singleton}

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.inject.Provides
import com.twitter.finagle.Http
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.routing.HttpRouter
import com.twitter.finatra.http.{Controller, HttpServer}
import com.twitter.finatra.httpclient.modules.HttpClientModule
import com.twitter.finatra.httpclient.{HttpClient, RequestBuilder}
import com.twitter.finatra.json.FinatraObjectMapper
import com.twitter.inject.TwitterModule
import com.twitter.util.Base64StringEncoder
import com.typesafe.config.ConfigFactory

/**
  * Created by lmarini on 1/5/16.
  */
object FinatraServerMain extends FinatraServer

class FinatraServer extends HttpServer {

  override val modules = Seq(MyModule1, DAPHttpClientModule)

  override def configureHttp(router: HttpRouter) {
    router
      .add[ExampleController]
      .add[HelloWorldController]
      .add[DAPController]
      .add[DTSController]
  }
}

object DAPHttpClientModule extends HttpClientModule {
  private val conf = ConfigFactory.load()
  override val dest = conf.getString("dap.url")
  override val hostname = "localhost"
  val user = conf.getString("dap.user")
  val password = conf.getString("dap.password")
  val encodedCredentials = "Basic " + Base64StringEncoder.encode(s"$user:$password".getBytes)
  override val defaultHeaders = Map("Authorization" -> encodedCredentials)
}

object MyObjectMapper extends ObjectMapper with ScalaObjectMapper

@Singleton
class DAPController @Inject()(httpClient: HttpClient) extends Controller {
  private val conf = ConfigFactory.load()
  get("/dap/:*") { request: Request =>
    val dapReq = RequestBuilder.get("/" + request.params("*"))
    val user = conf.getString("dap.user")
    val password = conf.getString("dap.password")
    val encodedCredentials = "Basic " + Base64StringEncoder.encode(s"$user:$password".getBytes)

    val dap = new HttpClient(
      hostname = "localhost",
      httpService = Http.newService(conf.getString("dap.url")),
      retryPolicy = None,
      defaultHeaders = Map("Authorization" -> encodedCredentials),
      mapper = new FinatraObjectMapper(MyObjectMapper))

    dap.execute(dapReq)
  }
}

@Singleton
class DTSController extends Controller {
  private val conf = ConfigFactory.load()

  post("/dts/:*") { request: Request =>
    val dapReq = RequestBuilder.post("/" + request.params("*"))
    val user = conf.getString("dap.user")
    val password = conf.getString("dap.password")
    val encodedCredentials = "Basic " + Base64StringEncoder.encode(s"$user:$password".getBytes)
    val dap = new HttpClient(
      hostname = "localhost",
      httpService = Http.newService(conf.getString("dap.url")),
      retryPolicy = None,
      defaultHeaders = Map("Authorization" -> encodedCredentials),
      mapper = new FinatraObjectMapper(MyObjectMapper))
    dap.execute(dapReq)
  }
  get("/dts/:*") { request: Request =>
    request.params("*")
    val dapReq = RequestBuilder.get("/" + request.params("*"))
    val user = conf.getString("dts.user")
    val password = conf.getString("dts.password")
    val encodedCredentials = "Basic " + Base64StringEncoder.encode(s"$user:$password".getBytes)
    val dts = new HttpClient(
      hostname = "localhost",
      httpService = Http.newService(conf.getString("dts.url")),
      retryPolicy = None,
      defaultHeaders = Map("Authorization" -> encodedCredentials),
      mapper = new FinatraObjectMapper(MyObjectMapper))
    dts.execute(dapReq)
  }
}

class ExampleController @Inject()(
  exampleService: ExampleService
) extends Controller {

  get("/ping") { request: Request =>
    exampleService.pong()
  }
}

class HelloWorldController extends Controller {

  get("/hi") { request: Request =>
    info("hi")
    "Hello " + request.params.getOrElse("name", "unnamed")
  }

  post("/hi") { hiRequest: HiRequest =>
    "Hello " + hiRequest.name + " with id " + hiRequest.id
  }
}

case class HiRequest(
  id: Long,
  name: String)

object MyModule1 extends TwitterModule {
  val key = flag("key", "defaultkey", "The key to use.")

  @Provides
  def providesExampleService: ExampleService = {
    new ExampleService()
  }
}

class ExampleService() {
  def pong() = "pong"
}