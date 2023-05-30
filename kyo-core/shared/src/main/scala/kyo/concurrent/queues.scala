package kyo.concurrent

import kyo._
import kyo.ios._
import kyo.options._
import org.jctools.queues._

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

object queues {

  class Queue[T] private[queues] (private[kyo] val unsafe: Queues.Unsafe[T]) {
    def capacity: Int =
      unsafe.capacity
    def size: Int > IOs =
      IOs(unsafe.size)
    def isEmpty: Boolean > IOs =
      IOs(unsafe.isEmpty)
    def isFull: Boolean > IOs =
      IOs(unsafe.isFull)
    def offer[S](v: T > S): Boolean > (IOs with S) =
      v.map(v => IOs(unsafe.offer(v)))
    def poll: Option[T] > IOs =
      IOs(unsafe.poll())
    def peek: Option[T] > IOs =
      IOs(unsafe.peek())
  }

  object Queues {

    private[kyo] trait Unsafe[T] {
      def capacity: Int
      def size: Int
      def isEmpty: Boolean
      def isFull: Boolean
      def offer(v: T): Boolean
      def poll(): Option[T]
      def peek(): Option[T]
    }

    class Unbounded[T] private[queues] (unsafe: Queues.Unsafe[T]) extends Queue[T](unsafe) {
      def add[S](v: T > S): Unit > (IOs with S) =
        v.map(offer).unit
    }

    private val zeroCapacity =
      new Unsafe[Any] {
        def capacity: Int          = 0
        def size: Int              = 0
        def isEmpty: Boolean       = true
        def isFull: Boolean        = true
        def offer(v: Any): Boolean = false
        def poll(): Option[Any]    = None
        def peek(): Option[Any]    = None
      }

    def bounded[T](capacity: Int, access: Access = Access.Mpmc): Queue[T] > IOs =
      IOs {
        capacity match {
          case 0 =>
            zeroCapacity.asInstanceOf[Queue[T]]
          case 1 =>
            new Queue(
                new AtomicReference[T] with Unsafe[T] {
                  def capacity = 1
                  def size     = if (get == null) 0 else 1
                  def isEmpty  = get == null
                  def isFull   = get != null
                  def offer(v: T) =
                    compareAndSet(null.asInstanceOf[T], v)
                  def poll() =
                    Option(getAndSet(null.asInstanceOf[T]))
                  def peek() =
                    Option(get)
                }
            )
          case _ =>
            access match {
              case Access.Mpmc =>
                bounded(new MpmcArrayQueue[T](capacity), capacity)
              case Access.Mpsc =>
                bounded(new MpscArrayQueue[T](capacity), capacity)
              case Access.Spmc =>
                bounded(new SpmcArrayQueue[T](capacity), capacity)
              case Access.Spsc =>
                bounded(new SpscArrayQueue[T](capacity), capacity)
            }
        }
      }

    def unbounded[T](access: Access = Access.Mpmc, chunkSize: Int = 8): Unbounded[T] > IOs =
      IOs {
        access match {
          case Access.Mpmc =>
            unbounded(new MpmcUnboundedXaddArrayQueue[T](chunkSize))
          case Access.Mpsc =>
            unbounded(new MpscUnboundedArrayQueue[T](chunkSize))
          case Access.Spmc =>
            unbounded(new MpmcUnboundedXaddArrayQueue[T](chunkSize))
          case Access.Spsc =>
            unbounded(new SpscUnboundedArrayQueue[T](chunkSize))
        }
      }

    private def unbounded[T](q: java.util.Queue[T]): Unbounded[T] =
      new Unbounded(
          new Unsafe[T] {
            def capacity: Int        = Int.MaxValue
            def size: Int            = q.size
            def isEmpty              = q.isEmpty()
            def isFull               = false
            def offer(v: T): Boolean = q.offer(v)
            def poll(): Option[T]    = Option(q.poll)
            def peek(): Option[T]    = Option(q.peek)
          }
      )

    private def bounded[T](q: java.util.Queue[T], _capacity: Int): Queue[T] =
      new Queue(
          new Unsafe[T] {
            def capacity: Int        = _capacity
            def size: Int            = q.size
            def isEmpty              = q.isEmpty()
            def isFull               = q.size >= _capacity
            def offer(v: T): Boolean = q.offer(v)
            def poll(): Option[T]    = Option(q.poll)
            def peek(): Option[T]    = Option(q.peek)
          }
      )
  }
}
