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
import kamon.testkit.{ BaseKamonSpec, WebServer, WebServerSupport }
import org.scalatest.Matchers

import scala.concurrent.duration._
import scala.concurrent._

class AkkaHttpServerErrorHandling extends BaseKamonSpec with Matchers {

  import WebServerSupport.Endpoints._

  implicit private val system = ActorSystem()
  implicit private val executor = system.dispatcher
  implicit private val materializer = ActorMaterializer()

  val timeoutStartUpServer = 10 minutes

  val interface = "0.0.0.0"
  val port = 8080

  val webServer = WebServer(interface, port)

  override protected def beforeAll(): Unit = {
    Kamon.start()
    Await.result(webServer.start(), timeoutStartUpServer)
  }

  override protected def afterAll(): Unit = {
    Await.result(webServer.shutdown(), timeoutStartUpServer)
    Kamon.shutdown()
  }

  "the Akka Http Server error handling instrumentation" should {
    "finish the trace with error when happen an exception on the user code" in {

      val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
        Http().outgoingConnection(interface, port)

      // Erase metrics recorder from previous tests.
      clean("UnnamedTrace", "trace")

      val validationFut: Future[_] = Source.single(HttpRequest(uri = tracePathFailure.withSlash))
        .via(connectionFlow)
        .runWith(Sink.head)
        .map(resp ⇒ {
          resp.status shouldBe InternalServerError
        })

      Await.result(validationFut, timeoutStartUpServer)

      val snapshot = takeSnapshotOf("UnnamedTrace", "trace")
      snapshot.histogram("elapsed-time").get.numberOfMeasurements should be(1)
      snapshot.counter("errors").get.count should be(1)

    }

    "finish the trace with error when the request is rejected" in {

      val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
        Http().outgoingConnection(interface, port)

      // Erase metrics recorder from previous tests.
      clean("UnnamedTrace", "trace")

      val validationFut: Future[_] = Source.single(HttpRequest(uri = "/wrong-url"))
        .via(connectionFlow)
        .runWith(Sink.head)
        .map(resp ⇒ {
          resp.status shouldBe NotFound
        })

      Await.result(validationFut, timeoutStartUpServer)

      val snapshot = takeSnapshotOf("UnnamedTrace", "trace")
      snapshot.histogram("elapsed-time").get.numberOfMeasurements should be(1)
      snapshot.counter("errors").get.count should be(1)

    }
  }

}
