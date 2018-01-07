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

package akka.http.impl.engine.client

import java.time.Instant

import akka.dispatch.ExecutionContexts
import akka.http.impl.engine.client.PoolInterfaceActor.PoolRequest
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import kamon.Kamon
import kamon.akka.http.{AkkaHttp, AkkaHttpMetrics}
import kamon.context.{Context, HasContext}
import kamon.metric.Histogram
import kamon.trace.SpanCustomizer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._

import scala.concurrent.Future
import scala.util._

@Aspect
class ClientRequestInstrumentation {

  @Around("execution(* akka.http.scaladsl.HttpExt.singleRequest(..)) && args(request, *, *, *)")
  def onSingleRequest(pjp: ProceedingJoinPoint, request: HttpRequest): Any = {
    val operationName = AkkaHttp.clientOperationName(request)
    val customizer = Kamon.currentContext().get(SpanCustomizer.ContextKey)
    val span = customizer.customize(
      Kamon.buildSpan(operationName)
        .withTag("component", "akka.http.client")
        .withTag("span.kind", "client")
        .withTag("http.url", request.getUri().toString)
        .withTag("http.method", request.method.value)
    ).start()

    val responseFuture = Kamon.withSpan(span, finishSpan = false) {
      pjp.proceed().asInstanceOf[Future[HttpResponse]]
    }

    responseFuture.onComplete {
      case Success(response) ⇒ {
        val status = response.status
        if(status.isFailure())
          span.addError(status.reason())

        span
          .tag("http.status_code", response.status.intValue())
          .finish()
      }
      case Failure(t) ⇒ {
        span.addError(t.getMessage, t).finish()
      }
    }(ExecutionContexts.sameThreadExecutionContext)

    responseFuture
  }

  trait HasContextWithNanos {
    def context: Context
    def nanos: Long
  }

  trait PoolInterfaceActorWithMetrics {
    def initialize(poolGateway: PoolGateway): Unit
    def recordWaitTime(waitTime: Long): Unit
  }

  def newHasInstantContext(capturedContext: Context): HasContextWithNanos = new HasContextWithNanos {
    override val context: Context = capturedContext
    override val nanos: Long = Kamon.clock().nanos()
  }

  def newPoolInterfaceActorWithMetrics(): PoolInterfaceActorWithMetrics = new PoolInterfaceActorWithMetrics {
    private var waitTimeHistogram: Histogram = _

    override def initialize(poolGateway: PoolGateway): Unit = {
      waitTimeHistogram = AkkaHttpMetrics.ClientPoolWaitTime.refine(Map(
        "host" -> poolGateway.hcps.host,
        "port" -> poolGateway.hcps.port.toString
      ))
    }

    override def recordWaitTime(waitTime: Long): Unit =
      if(waitTimeHistogram != null) waitTimeHistogram.record(waitTime)
  }

  @DeclareMixin("akka.http.impl.engine.client.PoolInterfaceActor")
  def mixinMetricsIntoPoolInterfaceActor(): PoolInterfaceActorWithMetrics = newPoolInterfaceActorWithMetrics()

  @After("execution(akka.http.impl.engine.client.PoolInterfaceActor.new(..)) && this(poolInterfaceActor) && args(poolGateway, *)")
  def afterPoolInterfaceActorConstructor(poolInterfaceActor: PoolInterfaceActor with PoolInterfaceActorWithMetrics, poolGateway: PoolGateway): Unit = {
    println("INITIALIZING")
    poolInterfaceActor.initialize(poolGateway)
  }

  @DeclareMixin("akka.http.impl.engine.client.PoolInterfaceActor.PoolRequest")
  def mixinContextIntoPoolRequest(): HasContextWithNanos = newHasInstantContext(Kamon.currentContext())

  @After("execution(akka.http.impl.engine.client.PoolInterfaceActor.PoolRequest.new(..)) && this(poolRequest)")
  def afterPoolRequestConstructor(poolRequest: HasContextWithNanos): Unit = {
    // Initialize the Context in the Thread creating the PoolRequest
    poolRequest.context
  }

  @Around("execution(* akka.http.impl.engine.client.PoolInterfaceActor.dispatchRequest(..)) && this(poolInterfaceActor) && args(poolRequest)")
  def aroundDispatchRequest(pjp: ProceedingJoinPoint, poolInterfaceActor: PoolInterfaceActor with PoolInterfaceActorWithMetrics, poolRequest: PoolRequest with HasContextWithNanos): Any = {
    val waitTime = Kamon.clock().nanos() -  poolRequest.nanos
    poolInterfaceActor.recordWaitTime(waitTime)

    val contextHeaders = Kamon.contextCodec().HttpHeaders.encode(poolRequest.context).values.map(c => RawHeader(c._1, c._2)).toList
    val requestWithContext = poolRequest.request.withHeaders(contextHeaders)

    pjp.proceed(Array(poolInterfaceActor, poolRequest.copy(request = requestWithContext)))
  }
}