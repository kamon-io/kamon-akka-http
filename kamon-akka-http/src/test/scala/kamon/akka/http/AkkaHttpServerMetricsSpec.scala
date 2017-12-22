
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
import akka.http.scaladsl._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import kamon.Kamon
import kamon.testkit.{BaseKamonSpec, MetricInspection, WebServer, WebServerSupport}
import kamon.trace.Span
import org.scalatest.Matchers

import scala.concurrent.duration._
import scala.concurrent._

class AkkaHttpServerMetricsSpec extends BaseKamonSpec with Matchers with MetricInspection {

  import WebServerSupport.Endpoints._

  implicit private val system = ActorSystem()
  implicit private val executor = system.dispatcher
  implicit private val materializer = ActorMaterializer()

  val timeoutStartUpServer = 10 second

  val interface = "127.0.0.1"
  val port = 8080

  val webServer = WebServer(interface, port)

  override protected def beforeAll(): Unit = {
    Await.result(webServer.start(), timeoutStartUpServer)
  }

  override protected def afterAll(): Unit = {
    Await.result(webServer.shutdown(), timeoutStartUpServer)
  }

  "the Akka Http Server metrics instrumentation" should {
    "record trace metrics for processed requests" in {

      val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
        Http().outgoingConnection(interface, port)

      val okResponsesFut = for (repetition ← 1 to 10) yield {
        Source.single(HttpRequest(uri = traceOk.withSlash))
          .via(connectionFlow)
          .runWith(Sink.head)
      } map (httpResponse ⇒ httpResponse.status shouldBe OK)

      val badRequestResponsesFut = for (repetition ← 1 to 5) yield {
        Source.single(HttpRequest(uri = traceBadRequest.withSlash))
          .via(connectionFlow)
          .runWith(Sink.head)
      } map (httpResponse ⇒ httpResponse.status shouldBe BadRequest)

      Await.result(Future.sequence(okResponsesFut ++ badRequestResponsesFut), timeoutStartUpServer)

      val availableKeys = Span.Metrics.ProcessingTime.partialRefine(Map.empty)
      availableKeys.map(k => Span.Metrics.ProcessingTime.refine(k).distribution(true).count).sum should be(15)

    }

    "record http server metrics for all the requests" in {

      val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
        Http().outgoingConnection("127.0.0.1", port)

      val okResponsesFut = for (repetition ← 1 to 10) yield {
        Source.single(HttpRequest(uri = metricsOk.withSlash))
          .via(connectionFlow)
          .runWith(Sink.head)
      } map (httpResponse ⇒ httpResponse.status shouldBe OK)

      val badRequestResponsesFut = for (repetition ← 1 to 5) yield {
        Source.single(HttpRequest(uri = metricsBadRequest.withSlash))
          .via(connectionFlow)
          .runWith(Sink.head)
      } map (httpResponse ⇒ httpResponse.status shouldBe BadRequest)

      Await.result(Future.sequence(okResponsesFut ++ badRequestResponsesFut), timeoutStartUpServer)

      Span.Metrics.ProcessingTime.refine(Map(
        "error" -> "false",
        "span.kind" -> "server",
        "operation" -> WebServerSupport.Endpoints.metricsOk.withSlash
      )).distribution(true).count should be(10)

      Span.Metrics.ProcessingTime.refine(Map(
        "error" -> "true",
        "span.kind" -> "server",
        "operation" -> "not-found"
      )).distribution(true).count should be(5)

      AkkaHttpServerMetrics.requestActive.distribution(true).max should be > 0L
      AkkaHttpServerMetrics.connectionOpen.distribution(true).max should be > 0L

    }
  }
}
