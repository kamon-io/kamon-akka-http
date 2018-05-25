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

package kamon.akka.http.instrumentation.kanela.advisor;

import akka.http.impl.engine.client.PoolInterfaceActor;
import akka.http.impl.engine.client.PoolRequestInstrumentation;
import kanela.agent.libs.net.bytebuddy.asm.Advice;

/**
 * Advisor akka.http.impl.engine.client.PoolInterfaceActor::dispatchRequest
 */
public class DispatcherRequestMethodAdvisor {
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 0, readOnly = false) PoolInterfaceActor.PoolRequest poolRequest) {
        poolRequest = (PoolInterfaceActor.PoolRequest) PoolRequestInstrumentation.attachContextTo(poolRequest);
    }
}
