package kyo.scheduler.regulator

import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.InternalTimer
import kyo.scheduler.util.*
import kyo.stats.internal.MetricReceiver
import kyo.stats.internal.UnsafeGauge
import scala.util.control.NonFatal

abstract class Regulator(
    loadAvg: () => Double,
    timer: InternalTimer,
    config: Config
) {
    import config.*

    private var step            = 0
    private val measurements    = new MovingStdDev(collectWindow)
    private val probesSent      = new LongAdder
    private val probesCompleted = new LongAdder
    private val adjustments     = new LongAdder
    private val updates         = new LongAdder

    protected def probe(): Unit
    protected def update(diff: Int): Unit

    private val collectTask =
        timer.schedule(collectInterval)(collect())

    private val regulateTask =
        timer.schedule(regulateInterval)(adjust())

    final private def collect(): Unit = {
        try {
            probesSent.increment()
            probe()
        } catch {
            case ex if NonFatal(ex) =>
                kyo.scheduler.bug(s"${getClass.getSimpleName()} regulator's probe collection has failed.", ex)
        }
    }

    protected def measure(v: Long): Unit = {
        probesCompleted.increment()
        stats.measurement.observe(v.toDouble)
        synchronized(measurements.observe(v))
    }

    final private def adjust() = {
        try {
            adjustments.increment()
            val jitter = synchronized(measurements.dev())
            val load   = loadAvg()
            if (jitter > jitterUpperThreshold) {
                if (step < 0) step -= 1
                else step = -1
            } else if (jitter < jitterLowerThreshold && load >= loadAvgTarget) {
                if (step > 0) step += 1
                else step = 1
            } else
                step = 0
            if (step != 0) {
                val pow = Math.pow(Math.abs(step), stepExp).toInt
                val delta =
                    if (step < 0) -pow
                    else pow
                stats.update.observe(delta)
                updates.increment()
                update(delta)
            } else
                stats.update.observe(0)
            stats.jitter.observe(jitter)
            stats.loadavg.observe(load)
        } catch {
            case ex if NonFatal(ex) =>
                kyo.scheduler.bug(s"${getClass.getSimpleName()} regulator's adjustment has failed.", ex)
        }
    }

    def stop(): Unit = {
        collectTask.cancel()
        regulateTask.cancel()
        stats.gauges.close()
        ()
    }

    protected val statsScope = kyo.scheduler.statsScope("regulator", getClass.getSimpleName())

    private object stats {
        val receiver    = MetricReceiver.get
        val loadavg     = receiver.histogram(statsScope, "loadavg")
        val measurement = receiver.histogram(statsScope, "measurement")
        val update      = receiver.histogram(statsScope, "update")
        val jitter      = receiver.histogram(statsScope, "jitter")
        val gauges = UnsafeGauge.all(
            receiver.gauge(statsScope, "probesSent")(probesSent.sum().toDouble),
            receiver.gauge(statsScope, "probesCompleted")(probesSent.sum().toDouble),
            receiver.gauge(statsScope, "adjustments")(adjustments.sum().toDouble),
            receiver.gauge(statsScope, "updates")(updates.sum().toDouble)
        )
    }

    protected def regulatorStatus(): Regulator.Status =
        Regulator.Status(
            step,
            measurements.avg(),
            measurements.dev(),
            probesSent.sum(),
            probesCompleted.sum(),
            adjustments.sum(),
            updates.sum()
        )
}

object Regulator {
    case class Status(
        step: Int,
        measurementsAvg: Double,
        measurementsJitter: Double,
        probesSent: Long,
        probesCompleted: Long,
        adjustments: Long,
        updates: Long
    ) {
        infix def -(other: Status): Status =
            Status(
                step,
                measurementsAvg,
                measurementsJitter,
                probesSent - other.probesSent,
                probesCompleted - other.probesCompleted,
                adjustments - other.adjustments,
                updates - other.updates
            )
    }
}
