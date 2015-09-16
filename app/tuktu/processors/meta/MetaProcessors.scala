package tuktu.processors.meta

import java.lang.reflect.Method
import java.util.concurrent.TimeoutException
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee._
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import tuktu.api._
import akka.routing.Broadcast

/**
 * Invokes a new generator for every DataPacket received
 * The first Datum, if available, is used to populate the config
 */
class GeneratorConfigProcessor(resultName: String) extends BaseProcessor(resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    var name = ""
    var fieldsToAdd: Option[List[JsObject]] = _

    override def initialize(config: JsObject) {
        // Get the name of the config file
        name = (config \ "name").as[String]

        // See if we need to populate the config file
        fieldsToAdd = (config \ "add_fields").asOpt[List[JsObject]]
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => Future {
        val datum = data.data.headOption.getOrElse(Map())

        // Resolve name with potential variables
        val nextName = utils.evaluateTuktuString(name, datum)

        // Open the config file to get contents, and evaluate as TuktuConfig
        val configFile = scala.io.Source.fromFile(Cache.getAs[String]("configRepo").getOrElse("configs") +
            "/" + nextName + ".json", "utf-8")
        val conf = utils.evaluateTuktuConfig(Json.parse(configFile.mkString).as[JsObject], datum)
        configFile.close

        // See if we need to add the config or not
        val newConfig = fieldsToAdd match {
            case Some(fields) => {
                // Add fields to our config
                val mapToAdd = (for (field <- fields) yield {
                    // Get source and target name
                    val source = (field \ "source").as[String]
                    val target = (field \ "target").as[String]

                    // Add to our new config, we assume only one element, otherwise this makes little sense
                    target -> datum(source).toString
                }).toMap

                // We must obtain a new configuration file with our fields added to the generator(s)
                // Modify generators
                val generators = {
                    (conf \ "generators").as[List[JsObject]].map(generator => {
                        // Get config part
                        val genConfig = (generator \ "config").as[JsObject]
                        val newGenConfig = genConfig ++ Json.toJson(mapToAdd).asInstanceOf[JsObject]

                        // Add new config to the generator
                        (generator - "config") ++ Json.obj("config" -> newGenConfig)
                    })
                }

                // Remove old ones and add new ones
                (conf - "generators") ++ Json.obj("generators" -> generators)
            }
            case None => conf // No fields to add, take normal evaluated tuktu config
        }

        // Invoke the new generator with custom config
        Akka.system.actorSelection("user/TuktuDispatcher") !
            new DispatchRequest(nextName, Some(newConfig), false, false, false, None)

        // We can still continue with out data
        data
    })
}

/**
 * This class is used to always have an actor present when data is to be streamed in sync
 */
class SyncStreamForwarder() extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    var remoteGenerator: ActorRef = null
    var sync: Boolean = false

    def receive() = {
        case setup: (ActorRef, Boolean) => {
            remoteGenerator = setup._1
            sync = setup._2
            sender ! true
        }
        case dp: DataPacket => sync match {
            case false => remoteGenerator ! dp
            case true => {
                sender ! Await.result((remoteGenerator ? dp).mapTo[DataPacket], timeout.duration)
            }
        }
        case sp: StopPacket => {
            remoteGenerator ! new StopPacket
            self ! PoisonPill
        }
    }
}

/**
 * Invokes a new generator for every DataPacket received
 * The first Datum, if available, is used to populate the config
 */
class GeneratorStreamProcessor(resultName: String) extends BaseProcessor(resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    val forwarder = Akka.system.actorOf(Props[SyncStreamForwarder])

    var processorConfig: JsObject = new JsObject(Seq())

    var sync: Boolean = false

    override def initialize(config: JsObject) {
        processorConfig = config
        sync = (config \ "sync").asOpt[Boolean].getOrElse(false)
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
        // Populate and dispatch remote generator with contents of first Datum
        val datum = data.data.headOption.getOrElse(Map())

        // Populate config
        val config = utils.evaluateTuktuConfig(processorConfig, datum)

        // Get the name of the config file
        val nextName = (config \ "name").as[String]
        // Node to execute on
        val nodes = (config \ "node").asOpt[String]
        // Get the processors to send data into
        val next = (config \ "next").as[List[String]]
        // Get the actual config, being a list of processors
        val processors = (config \ "processors").as[List[JsObject]]

        // Manipulate config and set up the remote actor
        val customConfig = Json.obj(
            "generators" -> List((Json.obj(
                "name" -> {
                    sync match {
                        case true  => "tuktu.generators.SyncStreamGenerator"
                        case false => "tuktu.generators.AsyncStreamGenerator"
                    }
                },
                "result" -> "",
                "config" -> Json.obj(),
                "next" -> next) ++
                (nodes match {
                    case Some(n) => Json.obj("nodes" -> Json.obj(
                        "type" -> "SingleNode",
                        "nodes" -> n,
                        "instances" -> 1))
                    case None => Json.obj()
                }))),
            "processors" -> processors)

        try {
            // Send a message to our Dispatcher to create the (remote) actor and return us the ActorRef
            val fut = Akka.system.actorSelection("user/TuktuDispatcher") ?
                new DispatchRequest(nextName, Some(customConfig), false, true, sync, None)
            val ar = Await.result(fut.mapTo[ActorRef], timeout.duration)
            // Tell the sync stream forwarder about the ActorRef
            val successFut = forwarder ? (ar, sync)
            Await.result(successFut.mapTo[Boolean], timeout.duration)
        } catch {
            case e: TimeoutException     => {}
            case e: NullPointerException => {}
        }

        // Send the result to the generator
        if (sync) {
            // Get the result from the generator
            val dataFut = forwarder ? data
            Await.result(dataFut.mapTo[DataPacket], timeout.duration)
        } else {
            forwarder ! data
            data
        }
    }) compose Enumeratee.onEOF(() => {
        forwarder ! new StopPacket
    })
}

/**
 * Invokes a new generator for every DataPacket received
 * The first Datum, if available, is used to populate the config
 */
class GeneratorConfigStreamProcessor(resultName: String) extends BaseProcessor(resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    var name: String = _
    var nodes: JsObject = _
    var next: List[String] = _
    var flowPresent = true
    var flowField: String = _
    var sendWhole = false

    override def initialize(config: JsObject) {
        // Get the name of the config file
        name = (config \ "name").as[String]
        // Node to execute on
        nodes = (config \ "nodes").asOpt[String] match {
            case Some(n) => Json.obj("nodes" -> Json.obj(
                "type" -> "SingleNode",
                "node" -> n,
                "instances" -> 1))
            case None => Json.obj()
        }
        // Get the processors to send data into
        next = (config \ "next").as[List[String]]

        // Check if the flow of processors if given or in an external JSON
        flowPresent = (config \ "flow_given").asOpt[Boolean].getOrElse(true)

        // The field of the JSON to load
        flowField = (config \ "flow_field").as[String]

        // Send the whole datapacket or in pieces
        sendWhole = (config \ "send_whole").asOpt[Boolean].getOrElse(false)
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
        if (!sendWhole) {
            data.data.foreach(datum =>
                forwardData(List(datum), utils.evaluateTuktuString(flowField, datum)))
        } else {
            forwardData(data.data, flowField)
        }

        data
    })

    def forwardData(data: List[Map[String, Any]], flowName: String) {
        val processors = {
            val configFile = scala.io.Source.fromFile(Cache.getAs[String]("configRepo").getOrElse("configs") +
                "/" + flowName + ".json", "utf-8")
            val cfg = utils.evaluateTuktuConfig(Json.parse(configFile.mkString).as[JsObject], data.headOption.getOrElse(Map()))
            configFile.close
            (cfg \ "processors").as[List[JsObject]]
        }
        // Manipulate config and set up the remote actor
        val customConfig = Json.obj(
            "generators" -> List((Json.obj(
                "name" -> "tuktu.generators.AsyncStreamGenerator",
                "result" -> "",
                "config" -> Json.obj(),
                "next" -> next) ++ nodes)),
            "processors" -> processors)

        // Send a message to our Dispatcher to create the (remote) actor and return us the actorref
        val fut = Akka.system.actorSelection("user/TuktuDispatcher") ? new DispatchRequest(name, Some(customConfig), false, true, false, None)
        // Make sure we get actorref set before sending data
        fut onSuccess {
            case generatorActor: ActorRef => {
                //send the data forward
                generatorActor ! new DataPacket(data)
                // Directly send stop packet
                generatorActor ! new StopPacket
            }
        }
    }
}

/**
 * Actor that deals with parallel processing
 */
class ParallelProcessorActor(processor: Enumeratee[DataPacket, DataPacket]) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    val (enumerator, channel) = Concurrent.broadcast[DataPacket]
    val sinkIteratee: Iteratee[DataPacket, Unit] = Iteratee.ignore

    /**
     * We must somehow keep track of the sending actor of each data packet. This state is kept within this helper class that
     * is to be instantiated for each data packet
     */
    class senderReturningProcessor(senderActor: ActorRef, dp: DataPacket) {
        // Create enumeratee that will send back
        val sendBackEnum: Enumeratee[DataPacket, DataPacket] = Enumeratee.map(dp => {
            senderActor ! dp
            dp
        })

        def runProcessor() = Enumerator(dp) |>> (processor compose sendBackEnum compose utils.logEnumeratee("")) &>> sinkIteratee
    }

    def receive() = {
        case sp: StopPacket => {
            self ! PoisonPill
        }
        case dp: DataPacket => {
            // Push to all async processors
            channel.push(dp)

            // Send through our enumeratee
            val p = new senderReturningProcessor(sender, dp)
            p.runProcessor()
        }
    }
}

/**
 * Executes a number of processor-flows in parallel
 */
class ParallelProcessor(resultName: String) extends BaseProcessor(resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    var actors: List[ActorRef] = null
    var merger: Method = null
    var mergerClass: Any = null

    override def initialize(config: JsObject) {
        // Process config
        val pipelines = (config \ "processors").as[List[JsObject]]

        // Set up the merger
        val mergerProcClazz = Class.forName((config \ "merger").as[String])
        mergerClass = mergerProcClazz.getConstructor().newInstance()
        merger = mergerProcClazz.getMethods.filter(m => m.getName == "merge").head

        // For each pipeline, build the enumeratee
        actors = for (pipeline <- pipelines) yield {
            val start = (pipeline \ "start").as[String]
            val procs = (pipeline \ "pipeline").as[List[JsObject]]
            val processorMap = (for (processor <- procs) yield {
                // Get all fields
                val processorId = (processor \ "id").as[String]
                val processorName = (processor \ "name").as[String]
                val processorConfig = (processor \ "config").as[JsObject]
                val resultName = (processor \ "result").as[String]
                val next = (processor \ "next").as[List[String]]

                // Create processor definition
                val procDef = new ProcessorDefinition(
                    processorId,
                    processorName,
                    processorConfig,
                    resultName,
                    next)

                // Return map
                processorId -> procDef
            }).toMap

            // Build the processor pipeline for this generator
            val (idString, processor) = {
                val pipeline = controllers.Dispatcher.buildEnums(List(start), processorMap, "ParalllelProcessor", None)
                (pipeline._1, pipeline._2.head)
            }
            // Set up the actor that will execute this processor
            Akka.system.actorOf(Props(classOf[ParallelProcessorActor], processor))
        }
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.map(data => {
        // Send data to actors
        val futs = for (actor <- actors) yield (actor ? data).asInstanceOf[Future[DataPacket]]

        // Get the results
        val results = Await.result(Future.sequence(futs), timeout.duration)

        // Apply the merger
        merger.invoke(mergerClass, results).asInstanceOf[DataPacket]
    })

}