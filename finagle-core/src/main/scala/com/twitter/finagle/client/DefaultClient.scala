package com.twitter.finagle.client

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.factory.{RefcountedFactory, StatsFactoryWrapper,   TimeoutFactory}
import com.twitter.finagle.filter.MonitorFilter
import com.twitter.finagle.loadbalancer.HeapBalancer
import com.twitter.finagle.service.FailureAccrualFactory
import com.twitter.finagle.service.{
  CloseOnReleaseService, ExpiringService, FailFastFactory, StatsFilter,
  TimeoutFilter
}
import com.twitter.finagle.stats.{
  BroadcastStatsReceiver, ClientStatsReceiver, RollupStatsReceiver,
  StatsReceiver, NullStatsReceiver
}
import com.twitter.finagle.tracing.{DefaultTracer, Tracer, TracingFilter}
import com.twitter.finagle.util.{DefaultTimer, DefaultMonitor}
import com.twitter.util.{Timer, Duration, Monitor}
import java.net.{SocketAddress, InetSocketAddress}

object DefaultClient {
  private val defaultFailureAccrual: ServiceFactoryWrapper =
    FailureAccrualFactory.wrapper(5, 5.seconds)(DefaultTimer.twitter)
}

/**
 * A default client implementation that does load balancing and
 * connection pooling. The only required argument is a binder,
 * responsible for binding concrete endpoints (named by
 * SocketAddresses).
 *
 * @param name A name identifying the client.
 *
 * @param endpointer A function used to create a ServiceFactory
 * to a concrete endpoint.
 *
 * @param pool The pool used to cache idle service (connection).
 *
 * @param maxIdletime The maximum time for which any `Service` is
 * permitted to be idle.
 *
 * @param maxLifetime The maximum lifetime for any `Service`
 *
 * @param requestTimeout The maximum time that any request is allowed
 * to take.
 *
 * @param failFast When enabled, installs a fail-fast module. See
 * [[com.twitter.finagle.service.FailFastFactory]]
 *
 * @param failureAccrual A failure accruing mechanism. Used to
 * gauge the health of the ServiceFactory. By default this uses
 * [[com.twitter.finagle.client.DefaultClient.defaultFailureAccrual]]
 *
 * @param serviceTimeout The maximum amount of time allowed for
 * acquiring a service. Defaults to infinity.
 */
case class DefaultClient[Req, Rep](
  name: String,
  endpointer: (SocketAddress, StatsReceiver) => ServiceFactory[Req, Rep],
  pool: StatsReceiver => Transformer[Req, Rep] = DefaultPool(),
  maxIdletime: Duration = Duration.Top,
  maxLifetime: Duration = Duration.Top,
  requestTimeout: Duration = Duration.Top,
  failFast: Boolean = true,
  failureAccrual: Transformer[Req, Rep] = { factory: ServiceFactory[Req, Rep] =>
    DefaultClient.defaultFailureAccrual andThen factory
  },
  serviceTimeout: Duration = Duration.Top,
  timer: Timer = DefaultTimer.twitter,
  statsReceiver: StatsReceiver = ClientStatsReceiver,
  hostStatsReceiver: StatsReceiver = NullStatsReceiver,
  tracer: Tracer  = DefaultTracer,
  monitor: Monitor = DefaultMonitor
) extends Client[Req, Rep] {
  /** Bind a socket address to a well-formed stack */
  val bindStack: SocketAddress => ServiceFactory[Req, Rep] = sa => {
    val hostStats = {
      val host = new RollupStatsReceiver(hostStatsReceiver.scope(
        sa match {
         case ia: InetSocketAddress => "%s:%d".format(ia.getHostName, ia.getPort)
         case other => other.toString
        }))
      val global = new RollupStatsReceiver(statsReceiver)
      BroadcastStatsReceiver(Seq(host, global))
    }

    val lifetimeLimited: Transformer[Req, Rep] = {
      val idle = if (maxIdletime < Duration.Top) Some(maxIdletime) else None
      val life = if (maxLifetime < Duration.Top) Some(maxLifetime) else None

      if (!idle.isDefined && !life.isDefined) identity else {
        factory => factory map { service =>
          val closeOnRelease = new CloseOnReleaseService(service)
          new ExpiringService(closeOnRelease, idle, life, timer, hostStats) {
            def onExpire() { closeOnRelease.close() }
          }
        }
      }
    }

    val timeBounded: Transformer[Req, Rep] = {
      if (requestTimeout == Duration.Top) identity else {
        val exception = new IndividualRequestTimeoutException(requestTimeout)
        factory => new TimeoutFilter(requestTimeout, exception, timer) andThen factory
      }
    }

    val fastFailed: Transformer[Req, Rep] =
      if (!failFast) identity else
        factory => new FailFastFactory(factory, hostStats.scope("failfast"), timer)

    val observed: Transformer[Req, Rep] = {
      val filter = new StatsFilter[Req, Rep](hostStats)
      factory => filter andThen factory
    }

    val monitored: Transformer[Req, Rep] = {
      val filter = new MonitorFilter[Req, Rep](monitor)
      factory => filter andThen factory
    }

    val newStack: SocketAddress => ServiceFactory[Req, Rep] = monitored compose
      observed compose
      failureAccrual compose
      timeBounded compose
      pool(hostStats) compose
      fastFailed compose
      lifetimeLimited compose
      (endpointer(_, hostStats))

    newStack(sa)
  }

  val newStack: Group[SocketAddress] => ServiceFactory[Req, Rep] = {
    val refcounted: Transformer[Req, Rep] = new RefcountedFactory(_)
  
    val timeLimited: Transformer[Req, Rep] = factory =>
      if (serviceTimeout == Duration.Top) factory else {
        val exception = new ServiceTimeoutException(serviceTimeout)
        new TimeoutFactory(factory, serviceTimeout, exception, timer)
      }
  
    val traced: Transformer[Req, Rep] = new TracingFilter[Req, Rep](tracer) andThen _
    val observed: Transformer[Req, Rep] = new StatsFactoryWrapper(_, statsReceiver)
  
    val noBrokersException = new NoBrokersAvailableException(name)
  
    val balanced: Group[SocketAddress] => ServiceFactory[Req, Rep] = group => {
      val endpoints = group map { addr => (addr, bindStack(addr)) }
      new HeapBalancer(
        endpoints,
        statsReceiver.scope("loadbalancer"),
        hostStatsReceiver.scopeSuffix("loadbalancer"),
        noBrokersException)
    }

    traced compose
      observed compose
      timeLimited compose
      refcounted compose
      balanced
  }

  def newClient(group: Group[SocketAddress]) = {
    val scoped: StatsReceiver => StatsReceiver = group match {
      case NamedGroup(groupName) => _.scope(groupName)
      case _ => _.scope(name)
    }

    copy(
      statsReceiver = scoped(statsReceiver),
      hostStatsReceiver = scoped(hostStatsReceiver)
    ).newStack(group)
  }
}
