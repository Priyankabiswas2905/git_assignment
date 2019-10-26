package edu.illinois.ncsa.fence.db

import java.util.UUID
import java.util.Date

import com.typesafe.config.ConfigFactory
import edu.illinois.ncsa.fence.Server.log
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.{Document, MongoClient, MongoCollection, MongoDatabase, WriteConcern}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ClowderMongodb {
  private val conf = ConfigFactory.load()

  // Mongo connection string, see https://docs.mongodb.com/manual/reference/connection-string/ for format
  private val connectionString = conf.getString("clowder.mongodb.connection")

  private val client: MongoClient = MongoClient(connectionString)

  private val db: MongoDatabase = client.getDatabase(conf.getString("clowder.mongodb.database"))

  private val users: MongoCollection[Document] = db.getCollection("social.users").withWriteConcern(WriteConcern.ACKNOWLEDGED)
  private val userApikeys: MongoCollection[Document] = db.getCollection("users.apikey").withWriteConcern(WriteConcern.ACKNOWLEDGED)

  /**
    * get "browndog" key from clowder, if the user not exist, create the user & key. if key not exist create key.
    * @param userId user id
    * @return a future of a UUID key
    */
  def createAndGetApiKey(userId: String): Future[String] = {
    for (optionUser <- users.find(and(equal("authMethod.method", "ldap"), equal("identityId.userId", userId))).toFuture().map(_.headOption);
         optionKey <- userApikeys.find(and(equal("identityId.providerId", "ldap"), equal("identityId.userId", userId), equal("name", "browndog"))).toFuture().map(_.headOption)
    ) yield {
      if(!optionUser.isDefined) {
        log.debug("create user in clowder")
        // create clowder with inentityId, other fields will be override when user login clowder with LDAP.
        val userDocument =
          Document(
            "status" -> "Active",
            "_typeHint" -> "models.ClowderUser",
            "firstName" -> "",
            "lastName" -> "",
            "fullName" -> "",
            "authMethod" -> Document("_typeHint" -> "securesocial.core.AuthenticationMethod", "method" -> "ldap"),
            "identityId" -> Document("_typeHint" -> "securesocial.core.IdentityId", "userId" -> userId, "providerId" -> "ldap"),
            "termsOfServices" -> Document("accepted" -> true, "acceptedDate" -> new Date, "acceptedVersion" -> "2016-06-06")
          )
        users.insertOne(userDocument).toFuture()
          .recoverWith { case e: Throwable => { log.error("Add clowder user", e); Future.failed(e) } }
      }
      if(! optionKey.isDefined) {
        log.debug("create user apikey in clowder")
        val newKey = UUID.randomUUID().toString
        val userApikey =
          Document(
            "_typeHint" -> "models.UserApiKey",
            "name" -> "browndog",
            "key" -> newKey,
            "identityId" -> Document("_typeHint" -> "securesocial.core.IdentityId","userId" -> userId, "providerId" -> "ldap"))
        userApikeys.insertOne(userApikey).toFuture()
          .recoverWith { case e: Throwable => { log.error("Add clowder api Key", e); Future.failed(e) } }
        newKey.toString
      } else {
        optionKey.get.getString("key")
      }
    }
  }
}
