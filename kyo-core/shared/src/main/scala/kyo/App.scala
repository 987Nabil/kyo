package kyo

import ios._
import clocks._
import consoles._
import resources._
import tries._
import aborts._
import aspects._
import randoms._
import concurrent.fibers._
import concurrent.timers._
import scala.concurrent.duration.Duration
import kyo.App.Effects
import scala.util.Try

abstract class App {

  final def main(args: Array[String]): Unit =
    App.run(run(args.toList))

  def run(
      args: List[String]
  ): Unit > Effects

}

object App {

  type Effects =
    Fibers with Resources with Consoles with Tries

  def run[T](timeout: Duration)(v: T > Effects)(implicit f: Flat[T, Effects]): T =
    IOs.run(runFiber(timeout)(v).block.map(_.get))

  def run[T](v: T > Effects)(implicit f: Flat[T, Effects]): T =
    run(Duration.Inf)(v)

  def runFiber[T](v: T > Effects)(implicit f: Flat[T, Effects]): Fiber[Try[T]] =
    runFiber(Duration.Inf)(v)

  def runFiber[T](timeout: Duration)(v: T > Effects)(implicit
      f: Flat[T, Effects]
  ): Fiber[Try[T]] = {
    def v1: T > (Fibers with Resources with Tries) = Consoles.run(v)
    def v2: T > (Fibers with Tries)                = Resources.run(v1)
    def v3: Try[T] > Fibers                        = Tries.run(v2)
    def v4: Try[T] > Fibers                        = Fibers.timeout(timeout)(v3)
    def v5: Try[T] > Fibers                        = Tries.run(v4).map(_.flatten)
    def v6: Try[T] > Fibers                        = Fibers.fork(v5).map(_.get)
    def v7: Fiber[Try[T]] > IOs                    = Fibers.run(v6)
    IOs.run(v7)
  }
}
