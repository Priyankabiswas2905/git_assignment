package edu.illinois.ncsa.fence.util

import java.io.StringWriter
import java.text.SimpleDateFormat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.ISO8601DateFormat
import com.fasterxml.jackson.datatype.jsr310.JSR310Module
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

/**
  * Utility methods to manipulate JSON using Jackson.
  */
object Jackson {
  val jacksonMapper = new ObjectMapper() with ScalaObjectMapper
  jacksonMapper.registerModule(DefaultScalaModule)
  // support for Java 8 time / dates
  jacksonMapper.registerModule(new JSR310Module())
  // date format
  jacksonMapper.setDateFormat(new ISO8601DateFormat())

  def stringToJSON(s: Any): String = {
    val out = new StringWriter
    jacksonMapper.writeValue(out, s)
    out.toString()
  }
}
