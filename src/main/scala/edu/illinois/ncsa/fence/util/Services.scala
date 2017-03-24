package edu.illinois.ncsa.fence.util

import java.net.URL

import com.twitter.finagle.Http
import com.twitter.util.{Base64StringEncoder, Try}
import com.typesafe.config.ConfigFactory

/**
  * Utility functions to help create services to connect to backend services.
  */
object Services {

  /** Load configuration file using typesafehub config */
  private val conf = ConfigFactory.load()

  /** Return a url for the key prefix */
  def getServiceURL(prefixKey: String): URL = {
    val confval = conf.getString(prefixKey + ".url")
    Try(new URL(confval)).getOrElse(new URL("http://" + confval))
  }

  /** Return host:port part of the key prefix */
  def getServiceHost(prefixKey: String): String = {
    val url = getServiceURL(prefixKey)
    if (url.getPort == -1) {
      url.getHost
    } else {
      s"${url.getHost}:${url.getPort}"
    }
  }

  /** Return the context-path of the key prefix */
  def getServiceContextPath(prefixKey: String): String = {
    getServiceURL(prefixKey).getPath
  }

  /** Create a service based on the key prefix, handles https */
  def getService(prefixKey: String) = {
    val url = Services.getServiceURL(prefixKey)
    if (url.getProtocol == "https") {
      Http.client.withTls(url.getHost).withStreaming(enabled = true).newService(getServiceHost(prefixKey))
    } else {
      Http.client.withStreaming(enabled = true).newService(getServiceHost(prefixKey))
    }
  }

  /** Create a basic auth based on the key prefix */
  def getServiceBasicAuth(prefixKey: String): String = {
    val user = conf.getString(s"$prefixKey.user")
    val password = conf.getString(s"$prefixKey.password")
    val encodedCredentials = Base64StringEncoder.encode(s"$user:$password".getBytes)
    "Basic " + encodedCredentials
  }
}
