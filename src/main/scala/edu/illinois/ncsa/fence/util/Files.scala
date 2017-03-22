package edu.illinois.ncsa.fence.util

import java.io.FileNotFoundException

/**
  * Created by lmarini on 10/23/16.
  */
object Files {
  def readResourceFile(path: String): String =
    Option(getClass.getResourceAsStream(path)).map(scala.io.Source.fromInputStream).map(_.mkString)
      .getOrElse(throw new FileNotFoundException(path))
}
