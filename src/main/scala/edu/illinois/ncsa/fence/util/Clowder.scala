package edu.illinois.ncsa.fence.util

import com.twitter.finagle.http.Request
import edu.illinois.ncsa.fence.Server._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

/**
  * Utilities specific to Clowder
  */
object Clowder {

  def extractFileURL(req: Request): String = {
    val mapper = new ObjectMapper() with ScalaObjectMapper
    val json = mapper.readTree(req.getContentString())
    val fileurl = json.findValue("fileurl").asText()
    log.debug("Extracted fileurl from body of request: " + fileurl)
    fileurl
  }
}
