package tuktu.nosql.processors.mongodb

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json._
import tuktu.api.{ BaseProcessor, DataPacket }
import tuktu.api.utils.{ MapToJsObject, evaluateTuktuString }
import tuktu.nosql.util.{ MongoSettings, MongoCollectionPool, stringHandler }
import reactivemongo.core.nodeset.Authenticate
import play.modules.reactivemongo.json._
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import reactivemongo.api.commands.WriteResult

/**
 * Inserts data into MongoDB
 */
class MongoDBInsertProcessor(resultName: String) extends BaseProcessor(resultName) {
    var fields = List[String]()
    var hosts: List[String] = _
    var database: String = _
    var coll: String = _
    var timeout: Int = _
    var user: Option[String] = _
    var pwd: String = _
    var admin: Boolean = _
    var scramsha1: Boolean = _

    override def initialize(config: JsObject) {
        // Set up MongoDB client
        hosts = (config \ "hosts").as[List[String]]
        database = (config \ "database").as[String]
        coll = (config \ "collection").as[String]

        // Get credentials
        user = (config \ "user").asOpt[String]
        pwd = (config \ "password").asOpt[String].getOrElse("")
        admin = (config \ "admin").asOpt[Boolean].getOrElse(true)
        scramsha1 = (config \ "ScramSha1").asOpt[Boolean].getOrElse(true)

        // What fields to write?
        fields = (config \ "fields").as[List[String]]

        timeout = (config \ "timeout").asOpt[Int].getOrElse(Cache.getAs[Int]("timeout").getOrElse(30))
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.map((data: DataPacket) => {
        val result = scala.collection.mutable.Map[(List[String], String, String), scala.collection.mutable.ListBuffer[JsObject]]()

        // Convert to JSON
        for (datum <- data.data) {
            result.getOrElseUpdate((hosts.map(evaluateTuktuString(_, datum)), evaluateTuktuString(database, datum), evaluateTuktuString(coll, datum)), scala.collection.mutable.ListBuffer[JsObject]()) += {
                fields match {
                    case Nil => MapToJsObject(datum, true)
                    case _   => MapToJsObject(datum.filter(elem => fields.contains(elem._1)), true)
                }
            }
        }

        // Bulk insert and await
        val futures = for (f <- result) yield {
            val collection = user match {
                case None => MongoCollectionPool.getCollection(MongoSettings(f._1._1, f._1._2, f._1._3))
                case Some(usr) => {
                    val credentials = admin match {
                        case true  => Authenticate("admin", usr, pwd)
                        case false => Authenticate(database, usr, pwd)
                    }
                    MongoCollectionPool.getCollectionWithCredentials(MongoSettings(f._1._1, f._1._2, f._1._3), credentials, scramsha1)
                }
            }
            collection.bulkInsert(f._2.toStream, false)
        }
        // Wait for all the results to be retrieved
        futures.foreach { x => if (!x.isCompleted) Await.ready(x, timeout seconds) }

        data
    })
}

/**
 * Insert the JSON Object contained in a field into MongoDB
 *
 */
class MongoDBFieldInsertProcessor(resultName: String) extends BaseProcessor(resultName) {
    var field: String = _
    var hosts: List[String] = _
    var database: String = _
    var coll: String = _
    var timeout: Int = _
    var user: Option[String] = _
    var pwd: String = _
    var admin: Boolean = _
    var scramsha1: Boolean = _

    override def initialize(config: JsObject) {
        // Set up MongoDB client
        hosts = (config \ "hosts").as[List[String]]
        database = (config \ "database").as[String]
        coll = (config \ "collection").as[String]

        // Get credentials
        user = (config \ "user").asOpt[String]
        pwd = (config \ "password").asOpt[String].getOrElse("")
        admin = (config \ "admin").as[Boolean]
        scramsha1 = (config \ "ScramSha1").as[Boolean]

        // What fields to write?
        field = (config \ "field").as[String]

        timeout = (config \ "timeout").asOpt[Int].getOrElse(Cache.getAs[Int]("timeout").getOrElse(30))
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.map((data: DataPacket) => {
        for (datum <- data.data if (datum.contains(field))) {
            val jobj: JsObject = datum(field) match {
                case jobj: JsObject         => jobj
                case jmap: Map[String, Any] => tuktu.api.utils.MapToJsObject(jmap, false)
            }
            val settings = MongoSettings(hosts.map(evaluateTuktuString(_, datum)), evaluateTuktuString(database, datum), evaluateTuktuString(coll, datum))
            val collection = user match {
                case None => MongoCollectionPool.getCollection(settings)
                case Some(usr) => {
                    val credentials = admin match {
                        case true  => Authenticate("admin", usr, pwd)
                        case false => Authenticate(database, usr, pwd)
                    }
                    MongoCollectionPool.getCollectionWithCredentials(settings, credentials, scramsha1)
                }
            }
            collection.insert(jobj)
        }
        data
    })
}