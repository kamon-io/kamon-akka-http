package akka.http.impl.engine.client

import akka.http.impl.engine.client.PoolInterfaceActor.PoolRequest
import akka.http.scaladsl.model.headers.RawHeader
import kamon.Kamon
import kamon.context.HasContext

object PoolRequestInstrumentation {
    def pepe(poolRequest: PoolRequest) = {
      val contextHeaders = Kamon.contextCodec().HttpHeaders.encode(poolRequest.asInstanceOf[HasContext].context).values.map(c => RawHeader(c._1, c._2))
      val requestWithContext = poolRequest.request.withHeaders(poolRequest.request.headers ++ contextHeaders)
      poolRequest.copy(request = requestWithContext)
    }
}
