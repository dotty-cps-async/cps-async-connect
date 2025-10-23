package cps.celoom

import cats.effect.*
import cats.effect.kernel.*
import cats.*
import cats.syntax.all.*

import cps.*
import cps.monads.catsEffect.{given, *}

import munit.CatsEffectSuite

import fs2.Stream
import fs2.io.file.{Files, Path}

import scala.collection.immutable.Map

/**
 * Isolated tests that reproduce the "Dispatcher already closed" issue
 * from cats-effect-loom/docs/issues/dca114.md
 *
 * These tests demonstrate the issue with closures escaping async blocks
 * when they contain nested higher-order functions with await in their arguments.
 *
 * Pattern:
 * 1. Inside async block: call HO function with function containing await as argument
 * 2. That HO function returns a closure
 * 3. Inside the returned closure: call another HO function with function containing await
 * 4. Return the closure from the async block
 * 5. Execute the closure outside the async block → "Dispatcher already closed"
 */
class DispatcherClosureEscapeTest extends CatsEffectSuite {

  // Helper method to create a temporary directory structure for testing
  def withTempDirectoryStructure[A](test: Path => IO[A]): IO[A] = {
    Files[IO].tempDirectory(None, "", None).use { rootDir =>
      // Create some subdirectories
      for {
        _ <- Files[IO].createDirectory(rootDir / "group1")
        _ <- Files[IO].createDirectory(rootDir / "group2")
        _ <- Files[IO].createDirectory(rootDir / "group3")
        // Create some files in each directory
        _ <- Stream.emit("content1").through(Files[IO].writeUtf8(rootDir / "group1" / "module1.txt")).compile.drain
        _ <- Stream.emit("content2").through(Files[IO].writeUtf8(rootDir / "group2" / "module2.txt")).compile.drain
        _ <- Stream.emit("content3").through(Files[IO].writeUtf8(rootDir / "group3" / "module3.txt")).compile.drain
        result <- test(rootDir)
      } yield result
    }
  }

  // Extension method to list subdirectories (similar to the one in the issue)
  extension (p: Path)
    def listSubdirs: Stream[IO, Path] = {
      Files[IO].list(p).evalFilter(path => Files[IO].isDirectory(path))
    }

  // Higher-order function that returns a closure
  // This is key to reproducing the issue
  def createDelayedProcessor[A, B](transform: A => B): A => () => B = {
    a => () => transform(a)
  }

  // Higher-order function that takes a function and applies it
  // When the function contains await, this creates the problematic pattern
  def applyWithTransform[A, B](items: List[A])(fn: A => B): List[B] = {
    items.map(fn)
  }

  // Higher-order function that returns a closure containing HO function calls
  def createNestedProcessor[A](innerFn: Int => A): () => List[A] = {
    () => {
      // This closure calls another HO function with the argument
      applyWithTransform(List(1, 2, 3))(innerFn)
    }
  }

  test("HO function with await arg returning closure with another HO function with await arg") {
    withTempDirectoryStructure { rootDir =>
      // This is the core pattern that reproduces the issue:
      // HO function takes function with await → returns closure →
      // closure calls another HO function with function with await

      // Create the closure inside async block
      val closureIO: IO[() => Int] = async[IO] {
        // Step 1: Call HO function (applyWithTransform) with a function containing await
        val firstValue = IO.delay(10).await

        // Step 2: Create a closure using another HO function (createDelayedProcessor)
        // This returns a closure that, when executed, will call yet another HO function
        val closure = createDelayedProcessor[Int, Int] { multiplier =>
          // Step 3: When this closure executes, it calls applyWithTransform (HO function)
          // with another function containing await
          val results = applyWithTransform(List(1, 2, 3)) { x =>
            IO.delay(x * multiplier).await  // await inside nested HO function's argument
          }
          results.sum + firstValue
        }

        // Apply the delayed processor and return the final closure
        closure(5)  // Returns () => Int
      }

      // Execute the closure OUTSIDE the async block
      // The closure will try to execute await with potentially closed dispatcher
      closureIO.map { thunk =>
        val result = thunk()  // This executes the closure which has await inside HO function
        assert(result > 0, s"Expected positive result but got $result")
      }
    }
  }

  test("triple nested HO functions - await in all levels") {
    withTempDirectoryStructure { rootDir =>
      // Triple nesting: HO function with await arg → returns closure →
      // closure calls another HO function with await arg → which calls third HO function with await arg

      val closureIO: IO[() => Int] = async[IO] {
        val fileCount = rootDir.listSubdirs.compile.toList.await.size

        // Level 1: Call first HO function with await in its argument
        val baseValue = applyWithTransform(List(fileCount)) { n =>
          IO.delay(n * 10).await  // First await in HO function arg
        }.head

        // Level 2: Create a closure using createDelayedProcessor (HO function)
        val level1Closure = createDelayedProcessor[Int, Int] { multiplier =>
          // Inside this closure: call applyWithTransform (second HO function) with await
          val level2Results = applyWithTransform(List(1, 2)) { x =>
            IO.delay(x * multiplier).await  // Second await in HO function arg
          }

          // Level 3: Create nested closure that calls third HO function with await
          val level2Closure = createNestedProcessor { n =>
            IO.delay(n + baseValue).await  // Third await in HO function arg
          }

          // Execute nested closure and combine results
          level2Results.sum + level2Closure().sum
        }

        // Return the outermost closure
        level1Closure(5)
      }

      // Execute the closure outside async - all await points should execute
      closureIO.map { thunk =>
        val result = thunk()
        assert(result > 0, s"Expected positive result but got $result")
      }
    }
  }

  test("evalMap with HO function taking await arg returning closure with nested HO await") {
    withTempDirectoryStructure { rootDir =>
      // Combine the original issue pattern (evalMap + listSubdirs) with nested HO functions with await
      // This is closest to the real-world code that triggered the bug

      val closuresIO: IO[List[() => Int]] = async[IO] {
        val closures = rootDir.listSubdirs
          .evalMap { dir =>
            async[IO] {
              val pathLen = dir.fileName.toString.length

              // Call HO function (createDelayedProcessor) that returns a closure
              // The closure will call another HO function (applyWithTransform) with await
              val processor = createDelayedProcessor[Int, Int] { baseValue =>
                // Inside this closure: call HO function with await in its argument
                val results = applyWithTransform(List(1, 2, 3)) { n =>
                  IO.delay(n + pathLen + baseValue).await  // await inside nested HO function
                }
                results.sum
              }

              // Apply and return the closure
              processor(10)  // Returns () => Int
            }
          }
          .compile
          .toList
          .await

        closures
      }

      // Execute all closures outside the async block
      closuresIO.map { thunks =>
        val results = thunks.map { thunk =>
          thunk()  // Each execution has await inside nested HO function
        }
        assert(results.size == 3, s"Expected 3 results but got ${results.size}")
      }
    }
  }
}
