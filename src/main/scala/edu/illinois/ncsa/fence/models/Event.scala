package edu.illinois.ncsa.fence.models

import java.util.Date

/** Events represent a submission of a file or URL for conversion or extraction */
case class Event(clientIP: String, date: Date, resource: String, eventType: String, user: String)


object Event {
  def unapply(values: Map[String,String]) = try{
    Some(Event(values("clientIP"), new Date(values("date")),  values("resource"), values("eventType"), values("user")))
  } catch{
    case ex: Exception => None
  }
}