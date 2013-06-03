package controllers

import play.api.libs.json.{JsValue, JsError, JsSuccess, Json}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumeratee
import play.api.mvc.{Action, Controller}
import scala.language.reflectiveCalls

import actors._
import utilities._
import models._
import models.TweetImplicits._
import play.api.libs.EventSource

/** Controller for serving main BirdWatch page including the WebSocket connection */
object Twitter extends Controller {

  /** Serves HTML page (static content for the most part, page gets updates through SSE) */
  def tweetList(q: String) = Action {
    implicit req => {
      RequestLogger.log(req, "/tweetList?q=" + q, 200)
      Ok(views.html.twitter.tweets(TweetsPublisher.topics, q))
    }
  }
  
  /** Enumeratee: Tweet to JsValue adapter */
  val tweetToJson: Enumeratee[Tweet, JsValue] = Enumeratee.map[Tweet] { t => Json.toJson(t) }
  
  /** Tests if all comma-separated words in q are contained in Tweet.text  */
  def containsAll(t: Tweet, q: String): Boolean = {
    val tokens = q.toLowerCase.split(",")
    val matches = tokens.foldLeft(0) {   // go through tokens with an accumulator for the number of matches 
      case (acc, token) if t.text.toLowerCase.contains(token) =>  acc + 1 // increase acc by 1 if token contained in text
      case (acc, token) => acc    // otherwise pass along acc 
    }
    matches == tokens.length      // true if all tokens contained, otherwise matches won't match number of tokens
  }

  /** Filtering Enumeratee applying containsAll function*/
  def textFilter(q: String) = Enumeratee.filter[Tweet] { t: Tweet => containsAll(t, q) }

  /** Serves Tweets as Server Sent Events over HTTP connection */
  def tweetFeed(q: String) = Action {
    implicit req => {
      RequestLogger.log(req, "/tweetFeed", 200)
      Ok.stream(TweetsConsumer.tweetsOut &> textFilter(q) &> tweetToJson &> EventSource()).as("text/event-stream")
    }
  }
  
  /** Stream informing clients about Tweet collection size */
  def countFeed = Action { Ok.stream(TweetsConsumer.countOut &> EventSource()).as("text/event-stream") }

  /** Serves raw Tweets as Server Sent Events over HTTP connection */
  def rawTweetFeed = Action {
    implicit req => {
      RequestLogger.log(req, "/rawTweetFeed", 200)
      Ok.stream(TweetsConsumer.rawTweetsOut &> EventSource()).as("text/event-stream")
    }
  }

  /** Controller Action serving raw Tweets as JSON going backwards in time from the time of request
    * @param n number of results to return
    */
  def rawTweetsJson(n: Int) = Action {
    implicit request => Async {
      Tweet.jsonLatestN(n).map {
        tweets => Ok(Json.toJson(tweets))
      }
    }
  }

  /** Controller Action serving Tweets as JSON going backwards in time from the time of request, filtered by q
    * @param n number of results to return
    */
  def tweetsJson(n: Int, q: String) = Action {
    implicit request => Async {
      Tweet.jsonLatestN(Math.min(n, 5000)).map {  // let's not allow really large result sets here -> constructs list
        rawTweets => {
          val tweets = rawTweets.par.map { x => TweetReads.reads(x) }.par.collect { 
            case JsSuccess(t, _) if containsAll(t, q) => t
          }
          Ok(Json.toJson(tweets.toList))
        }
      }
    }
  }

  /** Controller Action replaying the specified number of tweets from
    * the specified time in millis forward.
    * @param n number of results to return
    * @param delayMS milliseconds of delay between replayed tweets
    */
  def tweetReplay(n: Int, delayMS: Int) = Action {
    implicit req => {
      RequestLogger.log(req, "/tweets/replay/" + n, 200)
      Async {
        println("replay " + n)
        Tweet.jsonLatestN(n).map {
          tweets => tweets.reverse.foreach {
            x => {
              TweetReads.reads(x) match {
                case JsSuccess(t: Tweet, _) => TweetsConsumer.tweetChannel.push(WordCount.wordsChars(t)); Thread.sleep(delayMS)
                case JsError(msg) =>
              }
            }
          }
            Ok(Json.toJson(tweets))
        }
      }
    }
  }
  
}