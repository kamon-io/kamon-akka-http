package kamon.akka.http.instrumentation

import org.aspectj.lang.annotation.After
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import akka.event.BusLogging
import kamon.Kamon
import kamon.metric.instrument.Gauge.functionZeroAsCurrentValueCollector

@Aspect
class PoolConductorInstrumentation {
  val slotStatesField = Class.forName("akka.http.impl.engine.client.PoolConductor$SlotSelector$$anon$1").getDeclaredField("slotStates")
  val slotSelectorField = Class.forName("akka.http.impl.engine.client.PoolConductor$SlotSelector$$anon$1").getDeclaredField("$outer")
  val logField = Class.forName("akka.http.impl.engine.client.PoolConductor$SlotSelector").getDeclaredField("log")
  
  @Pointcut("execution(akka.http.impl.engine.client.PoolConductor$SlotSelector$$anon$1.new(..)) && this(logic)")
  def newGraphStageLogic(logic: AnyRef): Unit = {}
  
  private val HOST = raw"//(.+):".r
  private val PORT = raw":([0-9]+)".r

  private def getTags(graphStageLogic: AnyRef): Map[String,String] = {
    slotSelectorField.setAccessible(true)
    val slotSelector = slotSelectorField.get(graphStageLogic)
    
    logField.setAccessible(true)
    val log = logField.get(slotSelector).asInstanceOf[BusLogging]
    val host = HOST.findFirstMatchIn(log.logSource).map(_.group(1)).getOrElse("unknown")
    val port = PORT.findAllMatchIn(log.logSource).toVector.lastOption.map(_.group(1)).getOrElse("80")
    
    Map("target_host" -> host, "target_port" -> port)
  }
  
  private val states: Map[String,Class[_]] = Map(
    "idle" -> Class.forName("akka.http.impl.engine.client.PoolConductor$Idle$"),
    "unconnected" -> Class.forName("akka.http.impl.engine.client.PoolConductor$Unconnected$"),
    "loaded" -> Class.forName("akka.http.impl.engine.client.PoolConductor$Loaded"),
    "busy" -> Class.forName("akka.http.impl.engine.client.PoolConductor$Busy"))
  
  @After("newGraphStageLogic(logic)")
  def afterNewGraphStageLogic(logic: AnyRef): Unit = {
    slotStatesField.setAccessible(true)
    val slotStates = slotStatesField.get(logic).asInstanceOf[Array[_]]
    
    val tags = getTags(logic)    
    for ((name, stateClass) <- states) {
      // note: don't use "host" as a tag, since for Datadog, it will cause dogstatsd to swallow ALL _other_ default tags. 
      Kamon.metrics.gauge(s"http-client.pool.connections.${name}", tags) { 
        () => slotStates.count(s => s.getClass() eq stateClass).toLong
      }
    }
  }
  
  @Pointcut("execution(akka.http.impl.engine.client.PoolConductor$SlotSelector$$anon$1.postStop(..)) && this(logic)")
  def postStopGraphStageLogic(logic: AnyRef): Unit = {}
  
  @After("postStopGraphStageLogic(logic)")
  def afterPostStopGraphStageLogic(logic: AnyRef): Unit = {
    val tags = getTags(logic)    
    for ((name, _) <- states) {
      Kamon.metrics.removeGauge(s"http-client.pool.connections.${name}", tags)
    }
  }
}