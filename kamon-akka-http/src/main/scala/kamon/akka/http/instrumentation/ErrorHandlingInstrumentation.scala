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

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route, RoutingLog}
import akka.http.scaladsl.settings.{ParserSettings, RoutingSettings}
import akka.stream.Materializer
import kamon.Kamon
import kamon.akka.http.AkkaHttpExtension
import kamon.trace.Status.FinishedWithError
import kamon.trace.Tracer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{Around, Aspect}

import scala.concurrent.{ExecutionContextExecutor, Future}


@Aspect
class ErrorHandlingInstrumentation {

  @Around("execution(* akka.http.scaladsl.server.directives.ExecutionDirectives$.handleExceptions(..)) && args(exceptionHandler)")
  def onHandleExceptions(pjp: ProceedingJoinPoint,
                         exceptionHandler: ExceptionHandler): AnyRef = {

//    println("run onHandleExceptions")
    pjp.proceed(Array(new ExceptionHandlerWrapper(exceptionHandler)))
  }

  @Around("execution(* akka.http.scaladsl.server.directives.ExecutionDirectives$.handleRejections(..)) && args(rejectionHandler)")
  def onHandleExceptions(pjp: ProceedingJoinPoint,
                         rejectionHandler: RejectionHandler): AnyRef = {

//    println("run onHandleExceptions - handleRejections")
    pjp.proceed(Array(new RejectionHandlerWrapper(rejectionHandler)))
  }
}

@Aspect
class StartMetricsInstrumentation {

  @Around("execution(* akka.http.scaladsl.server.Route$.asyncHandler(..)) && args(route," +
    "routingSettings, parserSettings, materializer, routingLog, executionContext, rejectionHandler, exceptionHandler)")
  def onRouteHandler(pjp: ProceedingJoinPoint,
                     route:            Route,
                     routingSettings:  RoutingSettings,
                     parserSettings:   ParserSettings,
                     materializer:     Materializer,
                     routingLog:       RoutingLog,
                     executionContext: ExecutionContextExecutor,
                     rejectionHandler: RejectionHandler,
                     exceptionHandler: ExceptionHandler): AnyRef = {

    val requestHandler = pjp.proceed(Array(
      route,
      routingSettings,
      parserSettings,
      materializer,
      routingLog,
      executionContext,
      rejectionHandler,
      exceptionHandler)
    ).asInstanceOf[HttpRequest ⇒ Future[HttpResponse]]

    new RequestHandlerWrapper(requestHandler)
  }
}

class RequestHandlerWrapper(underlying: HttpRequest ⇒ Future[HttpResponse]) extends (HttpRequest ⇒ Future[HttpResponse]) {

  import scala.concurrent.ExecutionContext.Implicits.global

  override def apply(v1: HttpRequest): Future[HttpResponse] = {
//    println("Entro al request handler!!!!!!!!!!!!!!!!!!")

//    val metrics = AkkaHttpExtension.metrics
//    metrics.recordRequest()
//    underlying.apply(v1).map { httpResponse =>
//      Tracer.currentContext.collect { ctx ⇒
//        if (!ctx.isClosed) ctx.finish()
//
////        val response = grab(responseIn)
//        val finishWithError = ctx.status == FinishedWithError
//        metrics.recordResponse(httpResponse, ctx.name, finishWithError = finishWithError)
//
//        if (AkkaHttpExtension.settings.includeTraceTokenHeader)
//          includeTraceToken(httpResponse, AkkaHttpExtension.settings.traceTokenHeaderName, ctx.token)
//        else httpResponse
//
//      } getOrElse httpResponse
//    }
    underlying.apply(v1)
  }


  private def includeTraceToken(response: HttpResponse, traceTokenHeaderName: String, token: String): HttpResponse = response match {
    case response: HttpResponse ⇒ response.withHeaders(response.headers ++ Seq(RawHeader(traceTokenHeaderName, token)))
    case other                  ⇒ other
  }
}
