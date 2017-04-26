package edu.illinois.ncsa.fence.util

import java.io.FileNotFoundException

/**
  * File utilities.
  */
object Files {

  /** Find file absolute path in classpath. */
  def readResourceFile(path: String): String =
    Option(getClass.getResourceAsStream(path)).map(scala.io.Source.fromInputStream).map(_.mkString)
      .getOrElse(throw new FileNotFoundException(path))
}
