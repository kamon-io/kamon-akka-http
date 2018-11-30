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

package akka.http.impl.engine.client

import akka.http.impl.engine.client.PoolInterfaceActor.PoolRequest
import akka.http.scaladsl.model.headers.RawHeader
import kamon.Kamon
import kamon.context.HttpPropagation.HeaderWriter
import kamon.instrumentation.Mixin.HasContext

import scala.collection.mutable

object PoolRequestInstrumentation {


  def headerWriter(map: mutable.Map[String, String]) = new HeaderWriter {
    override def write(header: String, value: String): Unit = map.put(header, value)
  }

  def attachContextTo(poolRequest: PoolRequest): AnyRef = {
    val contextHeaders = mutable.Map[String, String]()
    Kamon.defaultHttpPropagation().write(poolRequest.asInstanceOf[HasContext].context, headerWriter(contextHeaders))
    val requestWithContext = poolRequest.request.withHeaders(poolRequest.request.headers ++ contextHeaders.map(c => RawHeader(c._1, c._2)))
    poolRequest.copy(request = requestWithContext)
  }
}