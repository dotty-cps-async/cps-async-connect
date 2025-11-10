package cps.celoom

import cats.effect.*
import cats.effect.std.Dispatcher
import cps.*
import cps.monads.catsEffect.given
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.DispatcherConfig

import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * This test confirms the deadlock scenario with Sequential Dispatcher.
 * Run with: sbt -Dcps.debug.await=true "catsEffectLoom/testOnly cps.celoom.DeadlockConfirmationTest"
 */
class DeadlockConfirmationTest extends CatsEffectSuite {

  // Helper functions that escape the async block (defined outside test class)
  def createProcessor[A, B](transform: A => B): A => () => B = {
    a => () => transform(a)
  }

  def applyToList[A, B](items: List[A])(fn: A => B): List[B] = {
    items.map(fn)
  }

  // This test WILL timeout - it proves the deadlock scenario exists
  // Sequential Dispatcher is NOT in public API, but we can test it using fromDispatcher
  // Run with: sbt -Dcps.debug.await=true "catsEffectLoom/testOnly cps.celoom.DeadlockConfirmationTest -- -z 'Sequential.*DEADLOCK'"
  test("Sequential Dispatcher - DEADLOCK CONFIRMED (will timeout after 15s)".ignore) {
    // Create Sequential Dispatcher directly (not available via DispatcherConfig)
    Dispatcher.sequential[IO](await = false).use { dispatcher =>
      given CpsRuntimeAwaitProvider[IO] = CpsCERuntimeAwaitProvider.fromDispatcher(dispatcher)
      
      println("\n=== DEADLOCK TEST WITH SEQUENTIAL ===")
      
      val closureIO: IO[() => Int] = async[IO] {
        println(s"[ASYNC] Creating closure on thread: ${Thread.currentThread().getName}")
        
        // Create a closure that will call await multiple times via map
        val processor = createProcessor[Int, Int] { multiplier =>
          println(s"[PROCESSOR] Called with $multiplier on thread: ${Thread.currentThread().getName}")
          
          // This calls await() 3 times concurrently via map
          val results = applyToList(List(1, 2, 3)) { x =>
            println(s"[MAP] Processing $x * $multiplier on thread: ${Thread.currentThread().getName}")
            val result = IO.delay(x * multiplier).await
            println(s"[MAP] Got result: $result")
            result
          }
          
          results.sum
        }
        
        // Return the closure
        processor(5)
      }
      
      println("[TEST] Executing closureIO to get the closure")
      
      // This will timeout with Sequential Dispatcher - we expect this to fail
      closureIO.flatMap { thunk =>
        println(s"[TEST] Got closure, calling it on thread: ${Thread.currentThread().getName}")
        IO {
          val result = thunk()  // This should deadlock!
          println(s"[TEST] Result: $result")
          result
        }
      }.timeout(10.seconds)
        .attempt
        .map { result =>
          // We expect a timeout exception (deadlock)
          assert(result.isLeft, "Expected timeout/deadlock with Sequential Dispatcher")
          println(s"[TEST] ✓ Confirmed: Sequential Dispatcher causes timeout as expected")
          println(s"[TEST] Error: ${result.left.getOrElse("none")}")
        }
    }
  }

  test("Parallel Dispatcher - should succeed (no deadlock)") {
    // Use the standard API which creates Parallel Dispatcher (only option)
    CpsCERuntimeAwaitProvider.resource[IO]().use { implicit provider =>
      
      println("\n=== TEST WITH PARALLEL (SHOULD WORK) ===")
      
      val closureIO: IO[() => Int] = async[IO] {
        println(s"[ASYNC] Creating closure on thread: ${Thread.currentThread().getName}")
        
        val processor = createProcessor[Int, Int] { multiplier =>
          println(s"[PROCESSOR] Called with $multiplier on thread: ${Thread.currentThread().getName}")
          
          val results = applyToList(List(1, 2, 3)) { x =>
            println(s"[MAP] Processing $x * $multiplier on thread: ${Thread.currentThread().getName}")
            val result = IO.delay(x * multiplier).await
            println(s"[MAP] Got result: $result")
            result
          }
          
          results.sum
        }
        
        processor(5)
      }
      
      println("[TEST] Executing closureIO to get the closure")
      
      closureIO.flatMap { thunk =>
        println(s"[TEST] Got closure, calling it on thread: ${Thread.currentThread().getName}")
        IO {
          val result = thunk()
          println(s"[TEST] Result: $result")
          assertEquals(result, 30)  // (1*5 + 2*5 + 3*5) = 30
        }
      }.timeout(10.seconds)
    }
  }
  
  test("Global Dispatcher (Parallel default) - should succeed") {
    import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global.given
    
    println("\n=== TEST WITH GLOBAL DISPATCHER (PARALLEL DEFAULT) ===")
    
    // Use actual test pattern from DispatcherClosureEscapeTest
    val closureIO: IO[() => Int] = async[IO] {
      println(s"[ASYNC] Creating closure")
      
      val processor = createProcessor[Int, Int] { multiplier =>
        val results = applyToList(List(1, 2, 3)) { x =>
          IO.delay(x * multiplier).await
        }
        results.sum
      }
      
      processor(5)
    }
    
    closureIO.flatMap { thunk =>
      IO {
        val result = thunk()
        assertEquals(result, 30)
      }
    }.timeout(10.seconds)
  }

  test("Parallel Dispatcher - handles 100 concurrent awaits (proves no thread exhaustion)") {
    import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global.given
    
    println("\n=== TEST 100 CONCURRENT AWAITS ===")
    
    val closureIO: IO[() => Int] = async[IO] {
      println(s"[100 AWAITS] Creating closure with large list")
      
      val processor = createProcessor[Int, Int] { multiplier =>
        // Create a list of 100 elements - MORE than io-compute thread pool size
        val largeList = (1 to 100).toList
        println(s"[100 AWAITS] Processing ${largeList.size} items (exceeds thread pool size)")
        
        val results = applyToList(largeList) { x =>
          IO.delay(x * multiplier).await
        }
        
        results.sum
      }
      
      processor(1)
    }
    
    closureIO.flatMap { thunk =>
      IO {
        val result = thunk()
        val expected = (1 to 100).sum  // 5050
        println(s"[100 AWAITS] ✓ Success! Result: $result")
        println(s"[100 AWAITS] ✓ Proves: Virtual threads don't exhaust thread pool")
        assertEquals(result, expected)
      }
    }.timeout(10.seconds)
  }

  test("PROOF: Sequential Dispatcher exhausts thread pool".ignore) {
    // This test demonstrates the EXACT deadlock mechanism
    // Use fromDispatcher to test Sequential (not in public API)
    Dispatcher.sequential[IO](await = false).use { dispatcher =>
      given CpsRuntimeAwaitProvider[IO] = CpsCERuntimeAwaitProvider.fromDispatcher(dispatcher)
      
      println("\n=== THREAD POOL EXHAUSTION TEST ===")
      
      val closureIO: IO[() => Int] = async[IO] {
        println(s"[EXHAUST] Creating closure")
        
        val processor = createProcessor[Int, Int] { multiplier =>
          // Use exactly as many awaits as there are io-compute threads
          // On typical system: 12 threads (based on CPU cores)
          val numThreads = Runtime.getRuntime.availableProcessors()
          val list = (1 to numThreads).toList
          println(s"[EXHAUST] Processing $numThreads items (= thread pool size)")
          
          val results = applyToList(list) { x =>
            println(s"[EXHAUST] Blocking thread with await #$x")
            IO.delay(x * multiplier).await
          }
          
          results.sum
        }
        
        processor(1)
      }
      
      
      println("[EXHAUST] Executing closure - will deadlock by exhausting thread pool")
      closureIO.flatMap { thunk =>
        IO {
          thunk()  // This WILL deadlock
        }
      }.timeout(10.seconds)
    }
  }
}
