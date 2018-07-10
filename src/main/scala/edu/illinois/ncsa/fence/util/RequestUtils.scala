package edu.illinois.ncsa.fence.util

import com.twitter.finagle.http.{Status, Version, Response}


object RequestUtils {


  /** Create a Bad Request response in cases where a query parameter is missing.
    *
    * @param param missing query parameter
    * @return a Bad Request response with message
    */
  def missingParam(param: String): Response = {
    val error = Response(Version.Http11, Status.BadRequest)
    error.setContentTypeJson()
    error.setContentString(s"""{"status":"error", "message":"Parameter '$param' required"}""")
    error
  }

}