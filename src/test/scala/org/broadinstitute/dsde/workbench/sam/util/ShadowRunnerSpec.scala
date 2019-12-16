package org.broadinstitute.dsde.workbench.sam.util

import java.util.concurrent.Executor

import cats.effect.{Clock, ContextShift, IO, Timer}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

class ShadowRunnerSpec extends FlatSpec with Matchers with ScalaFutures {
  lazy val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

  "ShadowRunner" should "timeout if the shadow takes too long" in {
    val methodCallInfo = MethodCallInfo("runTest", Array.empty, Array.empty)
    val testShadowResultReporter = new TestShadowResultReporter

    /**
      * TestShadowRunner creates a real and a shadow IO. The real one completes immediately. The shadow waits
      * for a semaphore to be released. The semaphore is acquired when runTest is called. The shadow will try
      * to acquire it again and will have to wait until it is release externally.
      */
    class TestShadowRunner extends ShadowRunner {
      override implicit val realContextShift: ContextShift[IO] = IO.contextShift(executionContext)
      override val shadowContextShift: ContextShift[IO] = IO.contextShift(executionContext)
      override val timer: Timer[IO] = IO.timer(executionContext)

      override val resultReporter: ShadowResultReporter = testShadowResultReporter
      override val shadowTimeout = 1 seconds

      val real = IO.pure(10)
      val shadow = timer.sleep((1 seconds) + shadowTimeout).map(_ => 15)

      def runTest(): IO[Int] = runWithShadow(methodCallInfo, real, shadow)
    }

    val testShadowRunner = new TestShadowRunner()
    val result = testShadowRunner.runTest()

    result.unsafeRunSync() should equal (10)

    val (actualMethodCallInfo, realTimedResult, shadowTimedResult) = testShadowResultReporter.resultFuture.futureValue
    actualMethodCallInfo should equal (methodCallInfo)
    realTimedResult.result should equal (Right(10))
    assert(shadowTimedResult.result.isLeft)
    shadowTimedResult.result.left.get.getMessage should equal ("shadow timed out after 1 second")
  }

  it should "run real dao and not be bothered by shadow failure" in {
    val methodCallInfo = MethodCallInfo("runTest", Array.empty, Array.empty)
    val testShadowResultReporter = new TestShadowResultReporter

    class TestShadowRunner extends ShadowRunner {
      override val realContextShift: ContextShift[IO] = IO.contextShift(executionContext)
      override val shadowContextShift: ContextShift[IO] = IO.contextShift(executionContext)
      override val timer: Timer[IO] = IO.timer(executionContext)
      override val resultReporter: ShadowResultReporter = testShadowResultReporter

      val real = IO.pure(10)
      val shadow = IO.raiseError(new Exception("shadow boom"))

      def runTest(): IO[Int] = runWithShadow(methodCallInfo, real, shadow)
    }

    val testShadowRunner = new TestShadowRunner()
    val result = testShadowRunner.runTest()

    result.unsafeRunSync() should equal (10)

    val (actualMethodCallInfo, realTimedResult, shadowTimedResult) = testShadowResultReporter.resultFuture.futureValue
    actualMethodCallInfo should equal (methodCallInfo)
    realTimedResult.result should equal (Right(10))
    shadowTimedResult.result should be ('isLeft)
  }

  it should "run real and shadow on correct thread pools" in {
    val methodCallInfo = MethodCallInfo("runTest", Array.empty, Array.empty)
    val testShadowResultReporter = new TestShadowResultReporter

    val realThreadName = "real-thread"
    val shadowThreadName = "shadow-thread"

    class TestShadowRunner extends ShadowRunner {
      override val realContextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutor(new Executor {
        override def execute(command: Runnable): Unit = new Thread(command, realThreadName).start() }))
      override val shadowContextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutor(new Executor {
        override def execute(command: Runnable): Unit = new Thread(command, shadowThreadName).start() }))
      override val timer: Timer[IO] = IO.timer(executionContext)
      override val resultReporter: ShadowResultReporter = testShadowResultReporter

      val probe = IO(Thread.currentThread().getName)

      def runTest(): IO[String] = runWithShadow(methodCallInfo, probe, probe)
    }

    val testShadowRunner = new TestShadowRunner()
    val result = testShadowRunner.runTest()

    result.unsafeRunSync() should equal (realThreadName)

    val (actualMethodCallInfo, realTimedResult, shadowTimedResult) = testShadowResultReporter.resultFuture.futureValue
    actualMethodCallInfo should equal (methodCallInfo)
    realTimedResult.result should equal (Right(realThreadName))
    shadowTimedResult.result should equal (Right(shadowThreadName))

  }

  "ShadowRunnerDynamicProxy" should "proxy calls to both real and shadow dao" in {
    val testShadowResultReporter = new TestShadowResultReporter
    trait TestDAO {
      def test(x: Int): IO[Int]
    }
    class RealDAO extends TestDAO {
      def test(x: Int) = IO.pure(x+1)
    }
    class ShadowDAO extends TestDAO {
      def test(x: Int) = IO.pure(x-1)
    }

    val realContextShift: ContextShift[IO] = IO.contextShift(executionContext)
    val shadowContextShift: ContextShift[IO] = IO.contextShift(executionContext)
    implicit val timer: Timer[IO] = IO.timer(executionContext)

    val clock: Clock[IO] = Clock.create[IO]
    val proxy = DaoWithShadow(new RealDAO, new ShadowDAO, testShadowResultReporter, realContextShift, shadowContextShift)

    val realResult = proxy.test(20).unsafeRunSync()
    realResult should be (21)
    val (methodCallInfo, realTimedResult, shadowTimedResult) = testShadowResultReporter.resultFuture.futureValue
    methodCallInfo.functionName should equal ("test")
    methodCallInfo.parameterNames should contain theSameElementsInOrderAs (Array("x"))
    methodCallInfo.parameterValues should contain theSameElementsInOrderAs (Array(20.asInstanceOf[AnyRef]))
    realTimedResult.result should equal (Right(21))
    shadowTimedResult.result should equal (Right(19))
  }
}

/**
  * Reports results for test purposes via a Future
  */
class TestShadowResultReporter extends ShadowResultReporter {
  private val resultPromise: Promise[(MethodCallInfo, TimedResult[_], TimedResult[_])] = Promise()
  /**
    * use this future to be notified when results are reported
    */
  val resultFuture: Future[(MethodCallInfo, TimedResult[_], TimedResult[_])] = resultPromise.future

  override val daoName: String = "testDAO"

  override def reportResult[T](methodCallInfo: MethodCallInfo, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T]): IO[Unit] = {
    resultPromise.success((methodCallInfo, realTimedResult, shadowTimedResult))
    IO.unit
  }

  override def reportShadowRunnerFailure(methodCallInfo: MethodCallInfo, regrets: Throwable): IO[Unit] = {
    resultPromise.failure(regrets)
    IO.unit
  }
}