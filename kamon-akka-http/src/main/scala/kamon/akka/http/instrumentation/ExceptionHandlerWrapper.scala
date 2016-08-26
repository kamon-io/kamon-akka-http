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

import akka.http.scaladsl.server.{ExceptionHandler, _}
import akka.http.scaladsl.settings.RoutingSettings
import kamon.trace.Tracer

import scala.collection.immutable.Seq

class ExceptionHandlerWrapper(exceptionHandler: ExceptionHandler) extends ExceptionHandler {

  override def withFallback(that: ExceptionHandler): ExceptionHandler = exceptionHandler.withFallback(that)

  override def seal(settings: RoutingSettings): ExceptionHandler = exceptionHandler.seal(settings)

  override def isDefinedAt(x: Throwable): Boolean = exceptionHandler.isDefinedAt(x)

  override def apply(v1: Throwable): Route = {
    println("running finishWithError")
    Tracer.currentContext.finishWithError(v1)
    exceptionHandler.apply(v1)
  }
}

class RejectionHandlerWrapper(underlying: RejectionHandler) extends RejectionHandler {

  override def apply(v1: Seq[Rejection]): Option[Route] = {
    println("running RejectionHandlerWrapper.apply")
    underlying.apply(v1)
  }
}
