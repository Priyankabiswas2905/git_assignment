package edu.illinois.ncsa.fence.models

import java.time.ZonedDateTime
import edu.illinois.ncsa.fence.Server.log

/** Events represent a submission of a file or URL for conversion or extraction */
case class Event(clientIP: String, date: ZonedDateTime, resource: String, eventType: String, user: String,
  resource_id: String)

object Event {
  def unapply(values: Map[String, String]) = try {
    Some(Event(values("clientIP"), ZonedDateTime.parse(values("date")), values("resource"), values("eventType"),
      values("user"), values("resource_id")))
  } catch {
    case ex: Exception => {
      log.error("Error parsing Map to create Event instance", ex)
      None
    }
  }
}