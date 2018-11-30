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

package kamon.akka.http.instrumentation

import akka.NotUsed
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow}
import akka.stream.stage._
import kamon.Kamon
import kamon.akka.http.{AkkaHttp, AkkaHttpMetrics}
import kamon.context.HttpPropagation.{HeaderReader, HeaderWriter}
import kamon.context.{Context => KamonContext}
import kamon.trace.Span

import scala.collection.mutable

/**
  * Wraps an {@code Flow[HttpRequest,HttpResponse]} with the necessary steps to output
  * the http metrics defined in AkkaHttpMetrics.
  *
  * Credits to @jypma.
  */
object ServerFlowWrapper {
  import AkkaHttp._

  def wrap(interface: String, port: Int) = new GraphStage[BidiShape[HttpRequest, HttpRequest, HttpResponse, HttpResponse]] {
    val openConnections = AkkaHttpMetrics.OpenConnections.refine("interface" -> interface, "port" -> port.toString)
    val activeRequests = AkkaHttpMetrics.ActiveRequests.refine("interface" -> interface, "port" -> port.toString)

    val requestIn = Inlet.create[HttpRequest]("request.in")
    val requestOut = Outlet.create[HttpRequest]("request.out")
    val responseIn = Inlet.create[HttpResponse]("response.in")
    val responseOut = Outlet.create[HttpResponse]("response.out")

    override val shape = BidiShape(requestIn, requestOut, responseIn, responseOut)

    override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {

      setHandler(requestIn, new InHandler {
        override def onPush(): Unit = {
          val request = grab(requestIn)
          val parentContext = extractContext(request)
          val span = Kamon.buildSpan(serverOperationName(request))
            .asChildOf(parentContext.get(Span.ContextKey))
            .withMetricTag("span.kind", "server")
            .withTag("component", "akka.http.server")
            .withTag("http.method", request.method.value)
            .withTag("http.url", request.uri.toString())
            .start()

          activeRequests.increment()

          // The only reason why it's safe to leave the Thread dirty is because the Actor instrumentation
          // will cleanup afterwards.
          Kamon.storeContext(parentContext.withKey(Span.ContextKey, span))
          push(requestOut, request)
        }
        override def onUpstreamFinish(): Unit = complete(requestOut)
      })

      setHandler(requestOut, new OutHandler {
        override def onPull(): Unit = pull(requestIn)
        override def onDownstreamFinish(): Unit = cancel(requestIn)
      })

      setHandler(responseIn, new InHandler {
        override def onPush(): Unit = {
          val response = grab(responseIn)
          val status = response.status.intValue()

          val span = if (addHttpStatusCodeAsMetricTag) {
            Kamon.currentSpan().tagMetric("http.status_code", status.toString())
          } else {
            Kamon.currentSpan().tag("http.status_code", status)
          }

          if(status == 404)
            span.setOperationName("unhandled")

          if(status >= 500 && status <= 599)
            span.addError(response.status.reason())

          activeRequests.decrement()
          span.finish()

          push(responseOut, includeTraceToken(response, Kamon.currentContext()))
        }
        override def onUpstreamFinish(): Unit = completeStage()
      })

      setHandler(responseOut, new OutHandler {
        override def onPull(): Unit = pull(responseIn)
        override def onDownstreamFinish(): Unit = cancel(responseIn)
      })

      override def preStart(): Unit = openConnections.increment()
      override def postStop(): Unit = openConnections.decrement()
    }
  }

  def apply(flow: Flow[HttpRequest, HttpResponse, NotUsed], iface: String, port: Int): Flow[HttpRequest, HttpResponse, NotUsed] =
    BidiFlow.fromGraph(wrap(iface, port)).join(flow)

  def headerWriter(map: mutable.Map[String, String]) = new HeaderWriter {
    override def write(header: String, value: String): Unit = map.put(header, value)
  }

  private def includeTraceToken(response: HttpResponse, context: KamonContext): HttpResponse = response match {
    case response: HttpResponse ⇒ {
      val contextHeaders = mutable.Map[String, String]()
      Kamon.defaultHttpPropagation().write(context, headerWriter(contextHeaders))
      response.withHeaders(
        response.headers ++ contextHeaders.map(k => RawHeader(k._1, k._2)))
    }
    case other                  ⇒ other
  }

  private def extractContext(request: HttpRequest) = Kamon.defaultHttpPropagation().read(new HeaderReader {
    override def read(header: String): Option[String] = request.headers.find(_.lowercaseName() == header.toLowerCase).map(_.value())

    override def readAll(): Map[String, String] = request.headers.map(h => (h.name(), h.value())).toMap
  })
}

