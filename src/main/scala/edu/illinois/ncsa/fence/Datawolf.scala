package edu.illinois.ncsa.fence

import com.twitter.finagle.Service
import com.twitter.finagle.http.Method.Post
import com.twitter.finagle.http.{Fields, Request, Response}
import com.twitter.finagle.http.Version.Http11
import com.twitter.util.Future
import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server.log
import edu.illinois.ncsa.fence.util.Services

import scala.util.parsing.json.{JSON, JSONObject}

/**
  * Services to interact with Datawolf. Datawolf is a scientific workflow management system. For more information see
  * https://opensource.ncsa.illinois.edu/bitbucket/projects/WOLF
  */
object Datawolf {

  // Datawolf backend service
  val dw = Services.getService("dw")

  /** Load configuration file using typesafehub config */
  private val conf = ConfigFactory.load()

  def datawolfPath(path: String): Service[Request, Response] = {
    log.debug("[Endpoint] Datawolf request")
    Service.mk { (req: Request) =>
      val prevBody = JSON.parseFull(req.contentString)
      val fenceURL = conf.getString("fence.hostname")
      val bodyMap = prevBody match {
        // Add fence url to the body arguments
        case Some(e: Map[String,Any]) => JSONObject(e + ("fence" -> fenceURL))
        case _ => prevBody
      }
      val newPathWithParameters = Services.getServiceContextPath("dw") + path + Server.getURIParams(req)
      val newReq = Request(Http11, Post, newPathWithParameters)
      req.headerMap.keys.foreach { key =>
        req.headerMap.get(key).foreach { value =>
          newReq.headerMap.add(key, value)
        }
      }
      val body = bodyMap.toString
      newReq.setContentString(body)
      newReq.headerMap.set(Fields.ContentLength, body.toString.length.toString)
      newReq.headerMap.set(Fields.Host, Services.getServiceHost("dw") + Services.getServiceContextPath("dw"))
      val rep = dw(newReq)
      rep.flatMap { r =>
        r.headerMap.remove(Fields.AccessControlAllowOrigin)
        r.headerMap.remove(Fields.AccessControlAllowCredentials)
        Future.value(r)
      }
      rep
    }
  }

}
