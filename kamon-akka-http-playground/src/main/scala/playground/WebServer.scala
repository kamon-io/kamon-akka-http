/*
 * =========================================================================================
 * Copyright © 2013-2016 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package playground

import akka.actor.{Actor, ActorSystem, Props}
import akka.event.Logging
import akka.http.scaladsl._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import kamon.logreporter.LogReporterSubscriber
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{Counter, Histogram}

import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.control.NoStackTrace

object WebServer extends App {

  Kamon.start()

  val config = ConfigFactory.load()

  val port: Int = config.getInt("http.port")
  val interface: String = config.getString("http.interface")

  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val logger = Logging(system, getClass)

  val routes = { // logRequestResult("akka-http-with-kamon") {
    get {
      path("ok") {
        complete {
          Thread.sleep(50)
          "ok"
        }
      } ~
        path("go-to-outside") {
          complete {
            Http().singleRequest(HttpRequest(uri = s"http://${config.getString("services.ip-api.host")}:${config.getString("services.ip-api.port")}/"))
          }
        } ~
        path("internal-error") {
          complete(HttpResponse(InternalServerError))
        } ~
        path("fail-with-exception") {
          throw new RuntimeException("Failed!") with NoStackTrace
        }
    }
  }

  val bindingFuture = Http().bindAndHandle(routes, interface, port)

//  val matGraph = RequestsGenerator.activate(100 millis, Vector(
//    s"/ok",
//    s"/go-to-outside",
//    s"/internal-error",
//    s"/fail-with-exception"))

  Kamon.metrics.subscribe("**", "**", system.actorOf(Props[AkkaHttpLogReporterSubscriber], "printer"))

  bindingFuture.map { serverBinding ⇒

    logger.info(s"Server online at http://$interface:$port/\nPress RETURN to stop...")

    StdIn.readLine()

    logger.info(s"Server is shutting down.")

//    matGraph.cancel()

    serverBinding
      .unbind() // trigger unbinding from the port
      .flatMap(_ ⇒ {
        Kamon.shutdown()
        system.terminate()
      }) // and shutdown when done
  }

}



class PrintAllMetrics extends Actor {
  def receive = {
    case TickMetricSnapshot(from, to, metrics) ⇒
      println("================================================================================")
      println(metrics.map({
        case (entity, snapshot) ⇒
          s"""${entity.category.padTo(20, ' ')} > ${entity.name}   ${entity.tags}
             |${snapshot.histograms}
           """.stripMargin
      }).toList.sorted.mkString("\n"))
  }
}

class AkkaHttpLogReporterSubscriber extends LogReporterSubscriber {

  override def printMetricSnapshot(tick: TickMetricSnapshot): Unit = {
    // Group all the user metrics together.
    val histograms = Map.newBuilder[String, Option[Histogram.Snapshot]]
    val counters = Map.newBuilder[String, Option[Counter.Snapshot]]
    val minMaxCounters = Map.newBuilder[String, Option[Histogram.Snapshot]]
    val gauges = Map.newBuilder[String, Option[Histogram.Snapshot]]

    tick.metrics foreach {
      case (entity, snapshot) if entity.category == "akka-actor"       ⇒ logActorMetrics(entity.name, snapshot)
      case (entity, snapshot) if entity.category == "akka-router"      ⇒ logRouterMetrics(entity.name, snapshot)
      case (entity, snapshot) if entity.category == "akka-dispatcher"  ⇒ logDispatcherMetrics(entity, snapshot)
      case (entity, snapshot) if entity.category == "akka-http-server" ⇒
        snapshot.minMaxCounter("request-active").foreach { m => minMaxCounters += (s"${entity.name} - request-active" -> Some(m)) }
        snapshot.minMaxCounter("connection-open").foreach { m => minMaxCounters += (s"${entity.name} - connection-open" -> Some(m)) }
        snapshot.counter("request-error").foreach { c => counters += (s"${entity.name} - request-error" -> Some(c)) }
      case (entity, snapshot) if entity.category == "executor-service" ⇒ logExecutorMetrics(entity, snapshot)
      case (entity, snapshot) if entity.category == "trace"            ⇒ logTraceMetrics(entity.name, snapshot)
      case (entity, snapshot) if entity.category == "histogram"        ⇒ histograms += (entity.name -> snapshot.histogram("histogram"))
      case (entity, snapshot) if entity.category == "counter"          ⇒ counters += (entity.name -> snapshot.counter("counter"))
      case (entity, snapshot) if entity.category == "min-max-counter"  ⇒ minMaxCounters += (entity.name -> snapshot.minMaxCounter("min-max-counter"))
      case (entity, snapshot) if entity.category == "gauge"            ⇒ gauges += (entity.name -> snapshot.gauge("gauge"))
      case (entity, snapshot) if entity.category == "system-metric"    ⇒ logSystemMetrics(entity.name, snapshot)
      case ignoreEverythingElse                                        ⇒
    }

    logMetrics(histograms.result(), counters.result(), minMaxCounters.result(), gauges.result())
  }
}

