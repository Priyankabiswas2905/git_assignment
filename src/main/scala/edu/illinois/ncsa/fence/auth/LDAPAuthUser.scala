package edu.illinois.ncsa.fence.auth

import javax.net.ssl.SSLSocketFactory

import com.twitter.finagle.http.{Version, _}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.{Base64StringEncoder, Future}
import com.typesafe.config.ConfigFactory
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{JVMDefaultTrustManager, SSLUtil, TrustAllTrustManager}
import edu.illinois.ncsa.fence.Server._

/**
  * Talk to LDAP and authenticate users
  */
class LDAPAuthUser extends SimpleFilter[Request, Response]{

  // Get LDAP configuration details
  private val conf = ConfigFactory.load()
  private val hostname = conf.getString("ldap.hostname")
  private val group = conf.getString("ldap.group")
  private val port = conf.getInt("ldap.port")
  private val baseDN = conf.getString("ldap.baseDN")
  private val trustAllCertificates = conf.getBoolean("ldap.trustAllCertificates")
  private val baseUserNamespace = conf.getString("ldap.userDN") + "," + baseDN
  private val baseGroupNamespace = conf.getString("ldap.groupDN") + "," + baseDN
  private val objectClass = conf.getString("ldap.objectClass")

  private val trustManager = if (trustAllCertificates) new TrustAllTrustManager() else JVMDefaultTrustManager.getInstance()
  private val sslUtil: SSLUtil = new SSLUtil(trustManager)
  private val socketFactory: SSLSocketFactory = sslUtil.createSSLSocketFactory()

  def apply(request: Request, continue: Service[Request, Response]) = {
    var ldapConnection: LDAPConnection = null

    log.trace("Validating credentials against LDAP")

    request.headerMap.get(Fields.Authorization) match {
      // If there is some header information
      case Some(header) =>
        // Extract credentials from header
        val credentials = new String(Base64StringEncoder.decode(header.substring(6))).split(":")
        // Check for required number of credentials
        if (credentials.size == 2) {

          val uid = credentials(0)
          val password = credentials(1)

          try {
            // Create LDAP connection
            ldapConnection = new LDAPConnection(socketFactory, hostname, port)

            // Bind user to the connection.
            // This will throw an exception if the user credentials do not match any LDAP entry.
            // This exception is later caught to refuse access to the user.
            ldapConnection.bind("uid=" + uid + "," + baseUserNamespace, password)

            // Filter to search the user's membership in the specified group
            val searchFilter: Filter = Filter.create("(&(objectClass=" + objectClass + ")(memberOf=cn=" + group + ","
              + baseGroupNamespace + ")(uid=" + uid + "))")

            // Perform group membership search
            val searchResult: SearchResult = ldapConnection.search(baseDN, SearchScope.SUB, searchFilter)
            log.trace(searchResult.getEntryCount + " LDAP entries returned.")

            // User is part of the specified group
            if (searchResult.getEntryCount == 1) {
              log.trace("User successfully validated. Forwarding request after checking against LDAP.")
              continue(request)
            }
            // User is not part of the specified group
            else {
              val errorResponse = Response(Version.Http11, Status.Forbidden)
              errorResponse.contentString = "User not authorized: Not a member of the specified LDAP group."
              log.error("User not a member of the LDAP group: " + group)
              Future(errorResponse)
            }
          }
          catch {
            case exception: LDAPException =>
              var errorResponse: Response = null

              exception.getResultCode match {
                case ResultCode.CONNECT_ERROR =>
                  errorResponse = Response(Version.Http11, Status.InternalServerError)
                  errorResponse.contentString = "An error occurred while connecting to LDAP server. Please try again later."
                  log.error("Error connecting to LDAP server: " + hostname + ":" + port)
                case ResultCode.OPERATIONS_ERROR =>
                  errorResponse = Response(Version.Http11, Status.Forbidden)
                  errorResponse.contentString = "User not authorized: Invalid username or password."
                  log.error("Error checking credentials. Invalid username or password. " + exception.getDiagnosticMessage)
                case _ =>
                  errorResponse = Response(Version.Http11, Status.InternalServerError)
                  errorResponse.contentString = "An unknown LDAP error occurred. Please try again later."
                  log.error("Unknown LDAP error. " + exception.getDiagnosticMessage)
              }
              Future(errorResponse)
          }
          finally {
            // Close connection
            if (ldapConnection != null)
              ldapConnection.close()
          }
        }
        else {
          log.error("Error checking credentials. Insufficient number of credentials submitted: " + credentials.size)
          val errorResponse = Response(Version.Http11, Status.Forbidden)
          errorResponse.contentString = "User not authorized: Username or password not provided."
          Future(errorResponse)
        }
      // No header information present
      case None =>
        log.error("Error checking credentials. No header.")
        val errorResponse = Response(Version.Http11, Status.Forbidden)
        errorResponse.contentString = "User not authorized: No credentials in the request header."
        Future(errorResponse)
    }
  }
}
