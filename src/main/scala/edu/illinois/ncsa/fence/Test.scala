package edu.illinois.ncsa.fence

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Http, Service}
import com.twitter.io.Reader
import com.twitter.util.{Await, Future}
import com.twitter.conversions.time._

/**
  * Testing Await.result
  *
  * Created by lmarini on 4/26/16.
  */
object Test {
  def main(args: Array[String]) {
    Http.server
      .withStreaming(enabled = true)
      .serve("0.0.0.0:8080", Service.mk[Request, Response] { req =>
        val writable = Reader.writable() // never gets closed
        Future.value(Response(req.version, Status.Ok, writable))
      })

    val client = Http.client.withStreaming(enabled = true).newService(s"/$$/inet/localhost/8080")

    val rsp0 = Await.result(client(Request()))
    // rsp0 stream is never closed
    println("first request succeeded")

    val rsp1 = Await.result(client(Request()))
    // hangs
    println("second request succeeded")
  }
}
