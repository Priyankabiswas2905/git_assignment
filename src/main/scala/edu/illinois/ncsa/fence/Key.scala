package edu.illinois.ncsa.fence

import java.util.UUID

/**
  * Long lived API Key.
  */
object Key {

  def newKey(): UUID = UUID.randomUUID()
}
