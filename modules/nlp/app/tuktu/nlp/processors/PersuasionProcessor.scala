package tuktu.nlp.processors

import tuktu.api.BaseProcessor
import play.api.libs.json.JsObject
import play.api.libs.iteratee.Enumeratee
import scala.concurrent.Future
import tuktu.api.DataPacket
import scala.concurrent.ExecutionContext.Implicits.global
import tuktu.api.utils
import tuktu.nlp.models.Rhetorics

/**
 * Computes the persuasiveness (power to convince) of a message
 */
class PersuasionProcessor(resultName: String) extends BaseProcessor(resultName) {
    var tokens: String =_
    var tags: String = _
    var lang: String = _
    var emotions: String = _
    
    override def initialize(config: JsObject) {
        tokens = (config \ "tokens").as[String]
        tags = (config \ "pos").as[String]
        lang = (config \ "language").as[String]
        emotions = (config \ "emotions").as[String]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => Future {
        for (datum <- data) yield {
            // Get the language
            val language = utils.evaluateTuktuString(lang, datum)
            // Get the tokens from data
            val tkns = datum(tokens) match {
                case t: String => t.split(" ")
                case t: Array[String] => t
                case t: Any => t.toString.split(" ")
            }
            // We need POS-tags before we can do anything, must be given in a field
            val posTags = datum(tags) match {
                case t: String => t.split(" ")
                case t: Array[String] => t
                case t: Any => t.toString.split(" ")
            }
            // We also require emotions
            val emotionValues = datum(emotions).asInstanceOf[Map[String, Double]]
            
            // Run the persuasion algorithm
            datum + (resultName -> Rhetorics.messagePersuasionScore(language, tkns.toList, posTags.toList,
                    Map(
                            "comments" -> 1.0,
                            "likes" -> 1.0,
                            "shares" -> 1.0,
                            "replies" -> 1.0,
                            "favorites" -> 1.0,
                            "retweets" -> 1.0
                    ), emotionValues))
        }
    })
}