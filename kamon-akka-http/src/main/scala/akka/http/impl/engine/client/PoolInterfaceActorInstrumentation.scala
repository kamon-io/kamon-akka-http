// Declared inside the akka package to rely a bit more on the compiler, and a bit less on reflection-only.
package akka.http.impl.engine.client

import org.aspectj.lang.annotation.After
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import akka.http.impl.settings.HostConnectionPoolSetup
import akka.stream.impl.Buffer
import kamon.Kamon

@Aspect
class PoolInterfaceActorInstrumentation {
  val inputBufferField = classOf[PoolInterfaceActor].getDeclaredField("akka$http$impl$engine$client$PoolInterfaceActor$$inputBuffer")
  val hcpsField = classOf[PoolInterfaceActor].getDeclaredField("akka$http$impl$engine$client$PoolInterfaceActor$$hcps")
  
  @Pointcut("execution(akka.http.impl.engine.client.PoolInterfaceActor.new(..)) && this(poolInterfaceActor)")
  def create(poolInterfaceActor: AnyRef): Unit = {}
  
  @After("create(actor)")
  def afterCreate(poolInterfaceActor: AnyRef): Unit = {
    inputBufferField.setAccessible(true)
    val buffer = inputBufferField.get(poolInterfaceActor).asInstanceOf[Buffer[_]]
    hcpsField.setAccessible(true)
    val hcps = hcpsField.get(poolInterfaceActor).asInstanceOf[HostConnectionPoolSetup]
    
    val tags = Map("target_host" -> hcps.host, "target_port" -> hcps.port.toString)
    Kamon.metrics.gauge("http-client.pool.queue.used", tags) { () => buffer.used.toLong }
    Kamon.metrics.gauge("http-client.pool.queue.capacity", tags) { () => buffer.capacity.toLong }
  }
  
  @Pointcut("execution(* akka.http.impl.engine.client.PoolInterfaceActor.afterStop(..)) && this(actor)")
  def stop(actor: PoolInterfaceActor): Unit = {}
  
  @After("stop(actor)")
  def afterStop(actor: PoolInterfaceActor): Unit = {
    hcpsField.setAccessible(true)
    val hcps = hcpsField.get(actor).asInstanceOf[HostConnectionPoolSetup]

    val tags = Map("target_host" -> hcps.host, "target_port" -> hcps.port.toString)
    Kamon.metrics.removeGauge("http-client.pool.queue.used", tags)
    Kamon.metrics.removeGauge("http-client.pool.queue.capacity", tags)
  }
}