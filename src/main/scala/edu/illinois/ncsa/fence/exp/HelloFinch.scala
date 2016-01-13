//package finch
//
//import com.twitter.finagle.Http
//import com.twitter.util.Await
//import io.finch._
//
//object HelloFinch extends App {
//  val api: Endpoint[String] = get("hello") {
//    Ok("Hello, World!")
//  }
//
//  val server = Http.serve(":8080", api.toService)
//  Await.ready(server)
//}