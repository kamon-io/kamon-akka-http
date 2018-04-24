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
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, Keep}
import akka.stream.stage._
import akka.util.ByteString
import kamon.Kamon
import kamon.akka.http.{AkkaHttp, AkkaHttpMetrics}
import kamon.context.{TextMap, Context => KamonContext}
import kamon.trace.Span

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.ExecutionContext


object ServerFlowWrapper {

  /**
    * A bidirectional flow of elements that consequently has two inputs and two
    * outputs, arranged like this:
    *
    * {{{
    *        +------------------------------------+
    *  In1 ~>|   +----------------------------+   |~> Out1
    *        |   |~> WrapOut1       WrapIn1 ~>|   |
    *        |   |                            |   |
    *        |   |<~ WrapIn2       WrapOut2 <~|   |
    * Out2 <~|   +----------------------------+   |<~ In2
    *        +------------------------------------+
    * }}}
    */
  def apply(
    flow: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed]
  ): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] = {

    BidiFlow.fromGraph(GraphDSL.create(flow, new HttpRequesetWrappedStage())((_, _) => NotUsed) { implicit builder =>
      (wrappedFlow, wrappingFlow) =>
        import GraphDSL.Implicits._

        wrappingFlow.wrapOut1 ~> wrappedFlow.in2
        wrappedFlow.out2 ~> wrappingFlow.wrapIn1
        wrappedFlow.out1 ~> wrappingFlow.wrapIn2
        wrappingFlow.wrapOut2 ~> wrappedFlow.in1

      BidiShape(wrappingFlow.in2, wrappingFlow.out2, wrappingFlow.in1, wrappingFlow.out1)

    })
  }

  private[instrumentation] def includeTraceToken(response: HttpResponse, context: KamonContext, span: Span)(implicit ex: ExecutionContext): HttpResponse = response.withHeaders(
      response.headers ++ Kamon.contextCodec().HttpHeaders.encode(context).values.map(k => RawHeader(k._1, k._2))
    ).transformEntityDataBytes(Flow[ByteString].map(identity).watchTermination()(Keep.right).mapMaterializedValue(_.onComplete(_ => span.finish())))

  private[instrumentation]  def extractContext(request: HttpRequest) = Kamon.contextCodec().HttpHeaders.decode(new TextMap {
    private val headersKeyValueMap = request.headers.map(h => h.name -> h.value()).toMap
    override def values: Iterator[(String, String)] = headersKeyValueMap.iterator
    override def get(key: String): Option[String] = headersKeyValueMap.get(key)
    override def put(key: String, value: String): Unit = {}
  })
}

/**
  * A bidirectional flow of elements that consequently has two inputs and two
  * outputs, arranged like this:
  *
  * {{{
  *        +------------------------------------+
  *  In1 ~>|   +----------------------------+   |~> Out1
  *        |   |~> WrapOut1       WrapIn1 ~>|   |
  *        |   |                            |   |
  *        |   |<~ WrapIn2       WrapOut2 <~|   |
  * Out2 <~|   +----------------------------+   |<~ In2
  *        +------------------------------------+
  * }}}
  */
final case class BidiWrappedShape[-In1, +WrapOut1, -WrapIn1, +Out1, -In2, +WrapOut2, -WrapIn2, +Out2](
  in1:  Inlet[In1 @uncheckedVariance],
  wrapOut1: Outlet[WrapOut1 @uncheckedVariance],
  wrapIn1: Inlet[WrapIn1 @uncheckedVariance],
  out1: Outlet[Out1 @uncheckedVariance],
  in2:  Inlet[In2 @uncheckedVariance],
  wrapOut2: Outlet[WrapOut2 @uncheckedVariance],
  wrapIn2: Inlet[WrapIn2 @uncheckedVariance],
  out2: Outlet[Out2 @uncheckedVariance]) extends Shape {
  override val inlets: immutable.Seq[Inlet[_]] = List(in1, wrapIn1, in2, wrapIn2)
  override val outlets: immutable.Seq[Outlet[_]] = List(out1, wrapOut1, out2, wrapOut2)

  override def deepCopy(): BidiWrappedShape[In1, WrapOut1, WrapIn1, Out1, In2, WrapOut2, WrapIn2, Out2] =
    BidiWrappedShape(
      in1.carbonCopy(),
      wrapOut1.carbonCopy(),
      wrapIn1.carbonCopy(),
      out1.carbonCopy(),
      in2.carbonCopy(),
      wrapOut2.carbonCopy(),
      wrapIn2.carbonCopy(),
      out2.carbonCopy()
    )

//  def reversed: Shape = copyFromPorts(inlets.reverse, outlets.reverse)

//  override def copyFromPorts(
//    inlets: immutable.Seq[Inlet[_]],
//    outlets: immutable.Seq[Outlet[_]]
//  ): Shape = {
//      require(inlets.size == 3, s"proposed inlets [${inlets.mkString(", ")}] do not fit BidiWrappedShape")
//      require(outlets.size == 3, s"proposed outlets [${outlets.mkString(", ")}] do not fit BidiWrappedShape")
//      BidiWrappedShape(
//        in1 = inlets(0),
//        wrapOut1 = outlets(0),
//        wrapIn1 = inlets(1),
//        out1 = outlets(1),
//        in2 = inlets(2),
//        wrapOut2 = outlets(2),
//        wrapIn2 = inlets(3),
//        out2 = outlets(3)
//      )
//  }
}



class HttpRequesetWrappedStage extends GraphStage[BidiWrappedShape[ByteString,  ByteString, HttpRequest, HttpRequest, HttpResponse, HttpResponse, ByteString, ByteString]] {

  import AkkaHttp._
  import ServerFlowWrapper._


  val requestStreamIn = Inlet[ByteString]("request.stream.in")
  val requestStreamOut = Outlet[ByteString]("request.stream.out")
  val requestIn = Inlet[HttpRequest]("request.in")
  val requestOut = Outlet[HttpRequest]("request.out")
  val responseIn = Inlet[HttpResponse]("response.in")
  val responseOut = Outlet[HttpResponse]("response.out")
  val responseStreamIn = Inlet[ByteString]("response.stream.in")
  val responseStreamOut = Outlet[ByteString]("response.stream.out")

  override val shape = BidiWrappedShape(requestStreamIn, requestStreamOut, requestIn, requestOut, responseIn, responseOut, responseStreamIn, responseStreamOut)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) { stageLogic =>



    private def failure(ex: Throwable) = {

      println("onUpstreamFailure")
      ex.printStackTrace()
    }

    /*
     * In1 ~> WrapOut1
     */
    setHandler(
      requestStreamIn, new InHandler {
        override def onPush(): Unit = {
          if (isAvailable(requestStreamIn)){
            val in = grab(requestStreamIn)

            println("requestStreamIn onPush")
            push(requestStreamOut, in)
          } else {
            complete(requestStreamOut)
          }
        }
        override def onUpstreamFinish(): Unit = {
          println(s"responseStreamIn onUpstreamFinish")
          complete(requestStreamOut)
        }
        override def onUpstreamFailure(ex: Throwable): Unit = {
          failure(ex)
          super.onUpstreamFailure(ex)
        }
      }
    )

    setHandler(
      requestStreamOut, new OutHandler {
        override def onPull(): Unit = {
          println("requestStreamOut onPull")
          pull(requestStreamIn)
        }
        override def onDownstreamFinish(): Unit = {
          println(s"requestStreamOut onDownstreamFinish")
          cancel(requestStreamIn)
        }
      }
    )
    /*
     * In1 ~> WrapOut1
     */





    /*
     * WrapIn1 ~> Out1
     */
    setHandler(
      requestIn, new InHandler {
        override def onPush(): Unit = {
          val request = grab(requestIn)
          println("requestIn onPush")
          val parentContext = extractContext(request)
          val span = Kamon.buildSpan(serverOperationName(request))
          .asChildOf(parentContext.get(Span.ContextKey))
          .withMetricTag("span.kind", "server")
          .withTag("component", "akka.http.server")
          .withTag("http.method", request.method.value)
          .withTag("http.url", request.uri.toString())
          .start()

          // The only reason why it's safe to leave the Thread dirty is because the Actor instrumentation
          // will cleanup afterwards.
          Kamon.storeContext(parentContext.withKey(Span.ContextKey, span))

          push(requestOut, request)
        }
        override def onUpstreamFinish(): Unit = {
          println(s"requestIn onUpstreamFinish")
          complete(requestOut)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          failure(ex)
          super.onUpstreamFailure(ex)
        }
      }
    )

    setHandler(
      requestOut, new OutHandler {
        override def onPull(): Unit = {
          println("requestOut onPull")
          pull(requestIn)
        }
        override def onDownstreamFinish(): Unit = {
          println(s"requestOut onDownstreamFinish")
          cancel(requestIn)
        }
      }
    )
    /*
     * WrapIn1 ~> Out1
     */




    /*
     * In2 ~> WrapIn2
     */
    setHandler(
      responseIn, new InHandler {
        override def onPush(): Unit = {
          val response: HttpResponse = grab(responseIn)
          println(s"responseIn onPush")

          val status = response.status.intValue()

          val span = if (addHttpStatusCodeAsMetricTag) {
            Kamon.currentSpan().tagMetric("http.status_code", status.toString())
          } else {
            Kamon.currentSpan().tag("http.status_code", status)
          }

          if (status == 404)
            span.setOperationName("unhandled")

          if (status >= 500 && status <= 599)
            span.addError(response.status.reason())

          span.mark("headers")

          //activeRequests.decrement()
          //span.finish()

          push(responseOut, includeTraceToken(response, Kamon.currentContext(), span)(stageLogic.materializer.executionContext))


        }
        override def onUpstreamFinish(): Unit = {
          println(s"responseIn onUpstreamFinish")
          complete(responseOut)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          failure(ex)
          super.onUpstreamFailure(ex)
        }
      }
    )

    setHandler(
      responseOut, new OutHandler {
        override def onPull(): Unit = {
          println(s"responseOut onPull")

          pull(responseIn)
        }
        override def onDownstreamFinish(): Unit = {
          println(s"responseOut onDownstreamFinish")

          cancel(responseIn)
        }
      }
    )
    /*
     * In2 ~> WrapIn2
     */



    /*
    * WrapIn2 ~> Out2
    */
    setHandler(
      responseStreamIn, new InHandler {
        override def onPush(): Unit = {
          val in = grab(responseStreamIn)
          println(s"responseStreamIn onPush: $in -> ${in.utf8String}")


          push(responseStreamOut, in)
        }
        override def onUpstreamFinish(): Unit = {
          println("responseStreamIn onUpstreamFinish. Completing stage")
          Kamon.currentSpan().finish()
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          failure(ex)
          super.onUpstreamFailure(ex)
        }
      }
    )

    setHandler(
      responseStreamOut, new OutHandler {
        override def onPull(): Unit = {
          println("requestStreamOut onPull")
          pull(responseStreamIn)
        }
        override def onDownstreamFinish(): Unit = {
          println(s"responseStreamOut onDownstreamFinish")
          cancel(responseStreamIn)
        }


      }
    )
    /*
     * WrapIn2 ~> Out2
     */

    override def preStart(): Unit = {

      println("prestart")
      super.preStart()
    }
    override def postStop(): Unit = {
      println("poststop")

      super.postStop()
    }
  }


}