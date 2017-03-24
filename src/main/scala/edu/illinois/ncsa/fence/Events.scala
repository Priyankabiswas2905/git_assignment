package edu.illinois.ncsa.fence

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import edu.illinois.ncsa.fence.Server.log
import edu.illinois.ncsa.fence.db.Mongodb
import edu.illinois.ncsa.fence.util.{Jackson, TwitterFutures}

/**
  * Created by lmarini on 3/22/17.
  */
object Events {
  /**
    * Return global statistics about the service. See [[models.Stats]] for the full list.
    *
    * @return Json document including all global statistics
    */
  def stats(): Service[Request, Response] = {
    log.debug("[Endpoint] Returning statistics ")
    Service.mk { (req: Request) =>
      Redis.getStats().flatMap { statistics =>
        val json = Jackson.stringToJSON(statistics)
        val r = Response()
        r.setContentTypeJson()
        r.setContentString(json)
        Future.value(r)
      }
    }
  }

  /**
    * List all events. A time interval can be specified by providing a starting time as milliseconds from UNIX epoch.
    * The "since" parameters specifies the starting time. The "until" parameters specifies the end time.
    *
    * @return Json array of matching events.
    */
  def events(): Service[Request, Response] = {
    log.debug("[Endpoint] Get multiple events ")
    Service.mk { (req: Request) =>
      val since = req.params.get("since")
      val until = req.params.get("until")
      val limit = req.params.getLongOrElse("limit", 100)
      // must cast from scala Future to twitter Future
      import TwitterFutures._
      import scala.concurrent.ExecutionContext.Implicits.global
      Mongodb.getEvents(since, until, limit.toInt).asTwitter.flatMap { events =>
        val json = Jackson.stringToJSON(events)
        val r = Response()
        r.setContentTypeJson()
        r.setContentString(json)
        Future.value(r)
      }
    }
  }

  /**
    * Get latest n events.
    * @return Json array of matching events.
    */
  def latestEvents(): Service[Request, Response] = {
    log.debug("[Endpoint] Get latest events ")
    Service.mk { (req: Request) =>
      val limit = req.params.getLongOrElse("limit", 100)
      // must cast from scala Future to twitter Future
      import TwitterFutures._
      import scala.concurrent.ExecutionContext.Implicits.global
      Mongodb.getLatestEvents(limit.toInt).asTwitter.flatMap { events =>
        val json = Jackson.stringToJSON(events)
        val r = Response()
        r.setContentTypeJson()
        r.setContentString(json)
        Future.value(r)
      }
    }
  }

  /**
    * Return an event by event id.
    *
    * @param eventId a string used internally to identify the event
    * @return Json document representing the specific event
    */
  def event(eventId: String): Service[Request, Response] = {
    log.debug("[Endpoint] Get event")
    Service.mk { (req: Request) =>
      // must cast from scala Future to twitter Future
      import TwitterFutures._
      import scala.concurrent.ExecutionContext.Implicits.global
      Mongodb.getEventById(eventId).asTwitter.flatMap { event =>
        val json = Jackson.stringToJSON(event)
        val r = Response()
        r.setContentTypeJson()
        r.setContentString(json)
        Future.value(r)
      }
    }
  }
}
