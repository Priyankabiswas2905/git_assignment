package edu.illinois.ncsa.fence

import com.twitter.finagle.http._
import com.twitter.util.Base64StringEncoder

/**
  * HTTP basic auth util methods.
  */
case class BasicAuth(username: String, password: String)

object BasicAuth {

  def extractCredentials(req: Request): Option[BasicAuth] = {
    req.headerMap.get(Fields.Authorization) match {
      case Some(header) =>
        val credentials = new String(Base64StringEncoder.decode(header.substring(6))).split(":")
        if (credentials.size == 2) {
          Some(BasicAuth(credentials(0), credentials(1)))
        } else {
          None
        }
      case None => None
    }
  }
}
