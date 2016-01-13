//package finch
//
//import java.util.UUID
//
//import com.twitter.finagle.http.{Request, Response}
//import com.twitter.finagle.param.Stats
//import com.twitter.finagle.stats.Counter
//import com.twitter.finagle.{Http, Service}
//import com.twitter.server.TwitterServer
//import com.twitter.util.Await
//import io.finch._
//import io.finch.circe._
//
//object Main extends TwitterServer {
//
//  case class User(name: String, age: Int, city: String)
//
//  val user: RequestReader[User] = (
//    param("name") ::
//      param("age").as[Int].shouldNot(beLessThan(18)) ::
//      paramOption("city").withDefault("Novosibirsk")
//    ).as[User]
//
//  case class TodoNotFound(id: UUID) extends Exception {
//    override def getMessage: String = s"Todo(${id.toString}) not found."
//  }
//
//
//
//  case class Todo(id: String, title: String, completed: Boolean, order: Int)
//
////  implicit val dateTimeEncoder: Encoder[DateTime] = Encoder.instance(a => a.getMillis.asJson)
////  implicit val uuidDecoder: Decoder[UUID] = Decoder.instance(a => a.as[String].map(new UUID(_)))
////implicit val uuidDecoder: DecodeRequest[UUID] = DecodeRequest(s => Try(new UUID(s.toString)))
//
//  val todos: Counter = statsReceiver.counter("todos")
//  val getTodos: Endpoint[List[Todo]] = get("todos") {
//    todos.incr()
//    Ok(List.empty[Todo])
//  }
////  val postedTodo: RequestReader[Todo] = body.as[UUID => Todo].map(_(UUID.randomUUID()))
//  val postedTodo: RequestReader[Todo] = body.as[Todo]
//  val postTodo: Endpoint[Todo] = post("todos" ? postedTodo) { t: Todo =>
//    todos.incr()
//    // add todo into the DB
//    Ok(t)
//  }
//
//  val api: Service[Request, Response] = (
//    getTodos :+: postTodo
//    ).handle({
//    case e: TodoNotFound => NotFound(e)
//  }).toService
//
//  def main(): Unit = {
//    val server = Http.server
//      .configured(Stats(statsReceiver))
//      .serve(":8081", api)
//
//    onExit { server.close() }
//
//    Await.ready(adminHttpServer)
//  }
//}