package edu.illinois.ncsa.fence.db

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.Date

import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Redis
import edu.illinois.ncsa.fence.Server.log
import edu.illinois.ncsa.fence.models.Event
import org.bson.types.ObjectId
import org.mongodb.scala._
import org.mongodb.scala.bson.BsonDateTime
import org.mongodb.scala.model.Filters._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.mongodb.scala.model.Indexes._


/**
  * Store events in MongoDB.
  *
  * TODO: Serialize cases classes using macros. This ability is in master but not in current release 1.2.1.
  * See https://github.com/rozza/mongo-scala-driver/pull/26/files.
  *
  */
object Mongodb {

  private val conf = ConfigFactory.load()

  // Mongo connection string, see https://docs.mongodb.com/manual/reference/connection-string/ for format
  private val connectionString = conf.getString("mongodb.connection")

  private val client: MongoClient = MongoClient(connectionString)

  private val db: MongoDatabase = client.getDatabase(conf.getString("mongodb.database"))

  private val events: MongoCollection[Document] = db.getCollection("events").withWriteConcern(WriteConcern.ACKNOWLEDGED)

  /**
    * Create indexes if they don't exist. Since observables returned by createIndex() are not executed
    * until invoked, we use toFuture() to invoke creating the index.
    */
  def setupIndexes(): Unit = {
    val logResult = (s: Seq[String]) => log.info(s"Creted index ${s.head}")
    events.createIndex(ascending("user")).toFuture().map(logResult)
    events.createIndex(descending("date")).toFuture().map(logResult)
    events.createIndex(ascending("resource")).toFuture().map(logResult)
    events.createIndex(ascending("resource_id")).toFuture().map(logResult)
  }

  /**
    * Retrieve events for a specific interval. Limit size of return (defaults to 100).
    *
    * @param since time in milliseconds since epoch
    * @param until time in milliseconds since epoch
    * @param limit maximum number of events
    * @return future of events
    */
  def getEvents(since: Option[String], until: Option[String], limit: Int): Future[Seq[Event]] = {
    val observable: Observable[Document] =
      if (since.isDefined && until.isDefined) {
        val start = Date.from(Instant.ofEpochMilli(since.get.toLong))
        val end = Date.from(Instant.ofEpochMilli(until.get.toLong))
        events.find(and(gte("date", start), lte("date", end))).limit(limit)
      } else if (since.isDefined) {
        val start = Date.from(Instant.ofEpochMilli(since.get.toLong))
        events.find(gte("date", start)).limit(limit)
      } else if (until.isDefined) {
        val end = Date.from(Instant.ofEpochMilli(until.get.toLong))
        events.find(lte("date", end)).limit(limit)
      } else {
        events.find().limit(limit)
      }

    val foo = observable.toFuture()
      .recoverWith { case e: Throwable => { log.error("Get events by time", e); Future.failed(e) } }
      .map(seq => seq.map(d => documentToEvent(d)))
    foo
  }

  /**
    * Get latest `limit` events.
    * @param limit max number of events returned
    * @return a future of events
    */
  def getLatestEvents(limit: Int): Future[Seq[Event]] = {
    events.find().sort(descending("date")).limit(limit).toFuture()
      .recoverWith { case e: Throwable => { log.error(s"Get latest ${limit}", e); Future.failed(e) } }
      .map(seq => seq.map(d => documentToEvent(d)))
  }

  /**
    * Store a new event.
    * @param eventType a string representing a type, currently "extraction" or "conversion"
    * @param resource a string representing the file, either as a URN or a URL
    * @param user id of the user submitting the job
    * @param clientIP client IP from which the submission originated
    * @param resource_id the id of the resource assigned by the backend service
    * @return future status of submission
    */
  def addEvent(eventType: String, resource: String, user: String, clientIP: String, resource_id: String):
  Future[Option[Completed]] = {
    val now = ZonedDateTime.now()
    val event = Event(clientIP, now, resource, eventType, user, resource_id)
    val insertObservable: Observable[Completed] = events.insertOne(eventToDocument(event))

    insertObservable.toFuture()
      .recoverWith { case e: Throwable => { log.error("Add event", e); Future.failed(e) } }
      .map {
        Redis.logRequestsQuota(user)
        log.debug(s"Event $eventType on $resource at $now from $clientIP")
        _.headOption
      }
  }

  /**
    * Get all events for a specific user.
    * @param user id of user
    * @return a future of a possible BSON Document
    */
  def getEventByUser(user: String): Future[Option[Document]] = {
    events.find(equal("user", user))
      .toFuture()
      .recoverWith { case e: Throwable => { log.error(s"Get events by user $user", e); Future.failed(e) } }
      .map(_.headOption)
  }

  /**
    * Get single envent by id.
    * @param id event id
    * @return a future of an event
    */
  def getEventById(id: String): Future[Option[Event]] = {
    events.find(equal("_id", new ObjectId(id)))
      .toFuture()
      .recoverWith { case e: Throwable => { log.error(s"Get event by id $id", e); Future.failed(e) } }
      .map(seq => seq.headOption.map(d => documentToEvent(d)))
  }

  /**
    * Get events by resource id. This could return multiple events in the case of the same url submitted.
    * @param resource the external URL or internal URN of the file
    * @return list of events (could be from multiple users in the case of the same RUL
    */
  def getEventsByResource(resource: String): Future[Seq[Event]] = {
    events.find(equal("resource_id", resource)).sort(descending("date")).toFuture()
      .recoverWith { case e: Throwable => { log.error(s"Get events by resource ${resource}", e); Future.failed(e) } }
      .map(seq => seq.map(d => documentToEvent(d)))
  }

  /**
    * Convert instance of Event to instance of BSON Document.
    * @param event an event
    * @return a BSON document
    */
  private def eventToDocument(event: Event): Document = {
    Document("clientIP" -> event.clientIP,
      "date" -> BsonDateTime(event.date.toInstant.getEpochSecond * 1000),
      "resource" -> event.resource,
      "eventType" -> event.eventType,
      "user" -> event.user,
      "resource_id" -> event.resource_id)
  }

  /**
    * Convert instance of BSON Document to instance of Event.
    * @param document a BSON document
    * @return an event
    */
  private def documentToEvent(document: Document): Event = {
    val clientIP = document.getString("clientIP")
    val date = ZonedDateTime.ofInstant(document.getDate("date").toInstant, ZoneId.systemDefault())
    val resource = document.getString("resource")
    val eventType = document.getString("eventType")
    val user = document.getString("user")
    val resource_id = document.getString("resource_id")
    Event(clientIP, date, resource, eventType, user, resource_id)
  }

}
