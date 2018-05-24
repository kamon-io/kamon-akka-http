/*
 * =========================================================================================
 * Copyright © 2013-2018 the kamon project <http://kamon.io/>
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

package kamon.akka.http.instrumentation.kanela.interceptor

import java.util.concurrent.Callable

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import kamon.Kamon
import kamon.akka.http.AkkaHttp
import kamon.trace.SpanCustomizer
import kamon.util.CallingThreadExecutionContext
import kanela.agent.libs.net.bytebuddy.implementation.bind.annotation.{Argument, SuperCall}

import scala.concurrent.Future
import scala.util.{Failure, Success}


object SingleRequestMethodInterceptor {
  def onSingleRequest(@SuperCall callable: Callable[Future[HttpResponse]], @Argument(0) request: HttpRequest): Future[HttpResponse] = {
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
      callable.call()
    }

    responseFuture.onComplete {
      case Success(response) =>
        val status = response.status
        if(status.isFailure())
          span.addError(status.reason())

        val spanWithStatusTag = if (AkkaHttp.addHttpStatusCodeAsMetricTag) {
          span.tagMetric("http.status_code", status.intValue.toString)
        } else {
          span.tag("http.status_code", status.intValue())
        }

        spanWithStatusTag.finish()
      case Failure(t) =>
        span.addError(t.getMessage, t).finish()
    }(CallingThreadExecutionContext)

    responseFuture
  }
}

