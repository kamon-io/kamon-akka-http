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

package kamon.akka.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import kamon.testkit._
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, Matchers, OptionValues, WordSpecLike}

import scala.concurrent.Future
import scala.concurrent.duration._

class AkkaHttpClientMetricsSpec extends WordSpecLike with Matchers with BeforeAndAfterAll with MetricInspection
  with Reconfigure with TestWebServer with Eventually with OptionValues {

  import AkkaHttpMetrics._
  import TestWebServer.Endpoints._

  implicit private val system = ActorSystem("http-server-metrics-instrumentation-spec")
  implicit private val executor = system.dispatcher
  implicit private val materializer = ActorMaterializer()

  val timeoutTest: FiniteDuration = 5 second
  val interface = "127.0.0.1"
  val port = 8086
  val webServer = startServer(interface, port)
  val clientPoolTags = Map(
    "host" -> interface,
    "port" -> port.toString
  )

  "the Akka HTTP client instrumentation" should {
    "track the wait time for connection pools" in {
      for(_ <- 1 to 8) yield {
        sendRequest(HttpRequest(uri = s"http://$interface:$port/$waitTwo"))
      }

      eventually(timeout(10 seconds)) {
        val x = ClientPoolWaitTime.refine(clientPoolTags).distribution(resetState = false)
        //println(x.buckets)

        x.max shouldBe(193)
      }
    }
  }

  val poolSettings = ConnectionPoolSettings(system)
    .withMaxConnections(1)
    .withMaxOpenRequests(2)

  def sendRequest(request: HttpRequest): Future[_] = {
    Http().singleRequest(request, settings = poolSettings).map(rsp => rsp.discardEntityBytes(materializer))
  }

  override protected def afterAll(): Unit = {
    webServer.shutdown()
  }
}

