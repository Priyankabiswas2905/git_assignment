package edu.illinois.ncsa.fence.util

import java.io.StringWriter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

/**
  * Utility methods to manipulate JSON using Jackson.
  */
object Jackson {
  val jacksonMapper = new ObjectMapper() with ScalaObjectMapper
  jacksonMapper.registerModule(DefaultScalaModule)

  def stringToJSON(s: Any): String = {
    val out = new StringWriter
    jacksonMapper.writeValue(out, s)
    out.toString()
  }
}
