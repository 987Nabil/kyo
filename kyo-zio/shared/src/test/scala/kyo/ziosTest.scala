package kyoTest

import kyo.*
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.Eventually.*
import scala.concurrent.Future
import zio.*

class ziosTest extends KyoTest:

    def runZIO[T](v: Task[T]): T =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(v).getOrThrow()
        )

    def runKyo(v: => Assertion < (Aborts[Throwable] & ZIOs)): Future[Assertion] =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.runToFuture(
                ZIOs.run(v)
            )
        )

    "Aborts[String]" in runKyo {
        val a: Nothing < (Aborts[String] & ZIOs) = ZIOs.get(ZIO.fail("error"))
        Aborts.run(a).map(e => assert(e.isLeft))
    }
    "Aborts[Throwable]" in runKyo {
        val a: Boolean < (Aborts[Throwable] & ZIOs) = ZIOs.get(ZIO.fail(new RuntimeException).when(false).as(true))
        Aborts.run(a).map(e => assert(e.isRight))
    }

    "Envs[Int]" in pendingUntilFixed {
        assertCompiles("""
            val a: Int < (Envs[Int] & ZIOs) = ZIOs.get(ZIO.service[Int])
            val b: Int < ZIOs               = Envs.run(10)(a)
            b.map(v => assert(v == 10))
        """)
    }
    "Envs[Int & Double]" in pendingUntilFixed {
        assertCompiles("""
            val a = ZIOs.get(ZIO.service[Int] *> ZIO.service[Double])
        """)
    }

    "A < ZIOs" in runKyo {
        val a: Int < ZIOs = ZIOs.get(ZIO.succeed(10))
        val b             = a.map(_ * 2)
        b.map(i => assert(i == 20))
    }

    "nested" in runKyo {
        val a: (String < ZIOs) < ZIOs = ZIOs.get(ZIO.succeed(ZIOs.get(ZIO.succeed("Nested"))))
        val b: String < ZIOs          = a.flatten
        b.map(s => assert(s == "Nested"))
    }

    "fibers" in runKyo {
        for
            v1 <- ZIOs.get(ZIO.succeed(1))
            v2 <- Fibers.init(2).map(_.get)
            v3 <- ZIOs.get(ZIO.succeed(3))
        yield assert(v1 == 1 && v2 == 2 && v3 == 3)
    }

    "interrupts" - {

        import java.util.concurrent.atomic.LongAdder

        def kyoLoop(a: LongAdder): Unit < IOs =
            IOs(a.increment()).map(_ => kyoLoop(a))

        def zioLoop(a: LongAdder): Task[Unit] =
            ZIO.attempt(a.increment()).flatMap(_ => zioLoop(a))

        if Platform.isJVM then

            "zio to kyo" in runZIO {
                val a = new LongAdder
                for
                    f <- ZIOs.run(kyoLoop(a)).fork
                    _ = eventually(a.sum() > 0)
                    _ <- f.interrupt
                    r <- f.await
                yield
                    a.reset()
                    eventually {
                        for _ <- 0 until 5 do
                            Thread.sleep(1)
                            assert(a.sum() == 0)
                            a.reset()
                    }
                    assert(r.isFailure)
                end for
            }
            "kyo to zio" in runKyo {
                val a = new LongAdder
                for
                    f <- Fibers.init(ZIOs.get(zioLoop(a)))
                    _ = eventually(a.sum() > 0)
                    _ <- f.interrupt
                    r <- f.getTry
                yield
                    a.reset()
                    eventually {
                        for _ <- 0 until 5 do
                            Thread.sleep(1)
                            assert(a.sum() == 0)
                            a.reset()
                    }
                    assert(r.isFailure)
                end for
            }
            "both" in runZIO {
                val a  = new LongAdder
                val a2 = new LongAdder
                val v =
                    for
                        _ <- ZIOs.get(zioLoop(a))
                        _ <- Fibers.init(kyoLoop(a2))
                    yield ()
                for
                    f <- ZIOs.run(v).fork
                    _ = eventually(a.sum() > 0)
                    _ = eventually(a2.sum() > 0)
                    _ <- f.interrupt
                    r <- f.await
                yield
                    a.reset()
                    eventually {
                        for _ <- 0 until 5 do
                            Thread.sleep(1)
                            assert(a.sum() == 0)
                            a.reset()
                    }
                    a2.reset()
                    eventually {
                        for _ <- 0 until 5 do
                            Thread.sleep(1)
                            assert(a2.sum() == 0)
                            a2.reset()
                    }
                    assert(r.isFailure)
                end for
            }
        end if
    }
end ziosTest
