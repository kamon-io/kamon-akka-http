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

package kamon.akka.http.instrumentation.kanela

import kamon.akka.http.instrumentation.kanela.advisor.DispatcherRequestMethodAdvisor
import kamon.akka.http.instrumentation.kanela.interceptor.SingleRequestMethodInterceptor
import kamon.akka.http.instrumentation.kanela.mixin.HasContextMixin
import kanela.agent.scala.KanelaInstrumentation

class ClientRequestInstrumentation extends KanelaInstrumentation {
  /**
    * Instrument:
    *
    * akka.http.scaladsl.HttpExt::singleRequest
    *
    */
  forTargetType("akka.http.scaladsl.HttpExt") { builder ⇒
    builder
      .withInterceptorFor(method("singleRequest"), SingleRequestMethodInterceptor)
      .build()
  }

  /**
    * Mix:
    *
    * akka.http.impl.engine.client.PoolInterfaceActor with HasContextMixin
    *
    */
  forTargetType("akka.http.impl.engine.client.PoolInterfaceActor$PoolRequest") { builder ⇒
    builder
      .withMixin(classOf[HasContextMixin])
      .build()
  }

  /**
    * Instrument:
    *
    * akka.http.impl.engine.client.PoolInterfaceActor::dispatchRequest
    *
    */
  forTargetType("akka.http.impl.engine.client.PoolInterfaceActor") { builder ⇒
    builder
      .withAdvisorFor(method("dispatchRequest"), classOf[DispatcherRequestMethodAdvisor])
      .build()
  }
}
