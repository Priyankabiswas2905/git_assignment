import com.twitter.finagle.http.{Response, Request}

import Server._

/**
  * Logging utility methods.
  */
object Logging {
  def debug(req: Request): Unit = {
    log.debug("= Request =")
    log.debug("Request: " + req)
    log.debug("Headers " + req.headerMap)
    log.debug("Body: " + req.contentString)
  }
  def debug(res: Response): Unit = {
    log.debug("= Response =")
    log.debug("Request: " + res)
    log.debug("Headers " + res.headerMap)
    log.debug("Body: " + res.contentString)
  }
}
