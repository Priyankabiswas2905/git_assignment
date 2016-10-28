package edu.illinois.ncsa.fence.util

import com.twitter.finagle._
import com.twitter.finagle.http.Fields
import edu.illinois.ncsa.fence.Redis
import edu.illinois.ncsa.fence.Server._

/**
  * Utilities to access external resources.
  */
object ExternalResources {

  /**
    * Use HTTP HEAD call to retrieve Content-Length for an external file and store it in Redis.
    *
    * Note that depending on the server implementation (chunked or not and
    * if content-length is the size of the full content) this might not return the correct file size.
    *
    * @param urlString the URL of the file we are interested in retrieving the size.
    * @param resourceType "extractions", "conversion"
    */
  def contentLengthFromHead(urlString: String, resourceType: String) {
    val url = new java.net.URL(urlString)
    val addr = {
      val port = if (url.getPort < 0) url.getDefaultPort else url.getPort
      Address(url.getHost, port)
    }
    val req = http.RequestBuilder().url(url).buildHead()
    val service = Http.newService(Name.bound(addr), "")
    val f = service(req) ensure {
      service.close()
    }
    f.onSuccess { r =>
      r.headerMap.get(Fields.ContentLength) match {
        case Some(length) =>
          val bytes = length.toInt
          log.debug(s"Adding $bytes bytes to $resourceType")
          Redis.logBytes(resourceType, bytes)
        case None => log.debug("no")
      }
      f.onFailure { cause: Throwable =>
        log.error(cause, "Error calling HEAD on remote file " + urlString)
      }
    }
  }
}
