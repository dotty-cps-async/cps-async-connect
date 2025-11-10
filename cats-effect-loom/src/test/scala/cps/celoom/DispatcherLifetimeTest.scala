package cps.celoom

import cats.effect.*
import cats.effect.kernel.*
import cats.*
import cats.syntax.all.*

import cps.*
import cps.monads.catsEffect.given
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global.given

import munit.CatsEffectSuite

import fs2.Stream
import fs2.io.file.{Files, Path}

import scala.collection.immutable.Map

/**
 * Tests related to the issue described in cats-effect-loom/docs/issues/dca114.md
 *
 * This file contains tests that demonstrate CORRECT patterns that work without
 * triggering the "Dispatcher already closed" error.
 *
 * Tests that reproduce the bug have been moved to DispatcherClosureEscapeTest.scala
 * for isolated debugging.
 *
 * The issue involves:
 * - CPS-transformed code using async[IO] and .await
 * - FS2 filesystem operations (particularly listSubdirs)
 * - Stream operations with evalMap containing await
 * - Closures with nested HO functions escaping async blocks
 */
class DispatcherLifetimeTest extends CatsEffectSuite {

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

  // Simulated data structures from the issue
  case class ModuleId(value: String)
  case class GroupId(id: String)
  case class GroupsAndModuleIds(groups: Map[GroupId, List[ModuleId]])

  // Simulated method to resolve module IDs - using async with await
  def resolveModuleIds(groupId: GroupId, dir: Path): IO[List[ModuleId]] = async[IO] {
    val modules = Files[IO]
      .list(dir)
      .evalFilter(p => Files[IO].isRegularFile(p))
      .evalMap { file =>
        IO.delay(ModuleId(file.fileName.toString))
      }
      .compile
      .toList
      .await

    modules
  }

  test("basic stream with await - no evalMap") {
    withTempDirectoryStructure { rootDir =>
      async[IO] {
        // First, let's test if basic stream operations with await work
        val dirs: List[Path] = rootDir.listSubdirs
          .compile
          .toList
          .await

        assert(dirs.size == 3, s"Expected 3 dirs but got ${dirs.size}")
      }
    }
  }

  test("stream with evalMap but no await inside evalMap") {
    withTempDirectoryStructure { rootDir =>
      async[IO] {
        // Test evalMap without await inside it - this should work fine
        val groups: Map[String, List[ModuleId]] = rootDir.listSubdirs
          .evalMap { d =>
            val groupId = d.fileName.toString
            Files[IO]
              .list(d)
              .evalFilter(p => Files[IO].isRegularFile(p))
              .evalMap(f => IO.delay(ModuleId(f.fileName.toString)))
              .compile
              .toList
              .map(modules => (groupId, modules))
          }
          .compile
          .to(Map)
          .await

        assert(groups.size == 3, s"Expected 3 groups but got ${groups.size}")
      }
    }
  }

  test("direct await on stream results - sequential processing") {
    withTempDirectoryStructure { rootDir =>
      async[IO] {
        // Process directories one by one using sequential awaits
        // This pattern works because we're not using await inside evalMap
        val dirs = rootDir.listSubdirs.compile.toList.await

        val results = dirs.map { dir =>
          val groupId = GroupId(dir.fileName.toString)
          val modules = resolveModuleIds(groupId, dir).await
          (groupId, modules)
        }

        assert(results.size == 3, s"Expected 3 results but got ${results.size}")
      }
    }
  }

  test("reproduce issue: await inside evalMap with nested async") {
    withTempDirectoryStructure { rootDir =>
      async[IO] {
        // This reproduces the original issue pattern:
        // - listSubdirs returns a Stream
        // - evalMap contains an async block with await
        // - This may cause dispatcher lifetime issues

        val result: Map[GroupId, List[ModuleId]] = rootDir.listSubdirs
          .evalMap { d =>
            async[IO] {
              val groupId = GroupId(d.fileName.toString)
              // Using await inside the async block within evalMap
              val modules = resolveModuleIds(groupId, d).await
              (groupId, modules)
            }
          }
          .compile
          .to(Map)
          .await

        assert(result.size == 3, s"Expected 3 groups but got ${result.size}")
        assert(result.contains(GroupId("group1")), "Missing group1")
        assert(result.contains(GroupId("group2")), "Missing group2")
        assert(result.contains(GroupId("group3")), "Missing group3")
      }
    }
  }

  test("reproduce issue: await on IO operations inside evalMap") {
    withTempDirectoryStructure { rootDir =>
      async[IO] {
        // Another pattern that may trigger the issue:
        // Using .await on simple IO operations inside evalMap lambda

        val groups: List[String] = rootDir.listSubdirs
          .evalMap { d =>
            async[IO] {
              // await on a simple IO operation
              IO.delay(d.fileName.toString).await
            }
          }
          .compile
          .toList
          .await

        assert(groups.size == 3, s"Expected 3 groups but got ${groups.size}")
        assert(groups.toSet == Set("group1", "group2", "group3"), s"Expected specific groups but got $groups")
      }
    }
  }

  test("reproduce issue: complex nested streams with multiple awaits") {
    withTempDirectoryStructure { rootDir =>
      async[IO] {
        // Most complex case: nested streams with multiple await points
        // This closely matches the original issue description

        val result = rootDir.listSubdirs
          .evalMap { dir =>
            async[IO] {
              val groupId = GroupId(dir.fileName.toString)
              // Nested stream processing with await
              val modules: List[ModuleId] = Files[IO]
                .list(dir)
                .evalFilter(p => Files[IO].isRegularFile(p))
                .evalMap { f =>
                  IO.delay(ModuleId(f.fileName.toString))
                }
                .compile
                .toList
                .await

              (groupId, modules)
            }
          }
          .compile
          .to(Map)
          .await

        assert(result.size == 3, s"Expected 3 groups but got ${result.size}")
      }
    }
  }

  test("reproduce issue: simulating original code structure with traverse") {
    withTempDirectoryStructure { rootDir =>
      async[IO] {
        // This most closely matches the original code from the issue:
        // GroupsAndModuleIds(
        //   config,
        //   config.repositoryRoot.listSubdirs.evalMap { d =>
        //     resolveModuleIds(...).await
        //   }.compile.to(Map).await,
        //   config.otherGroups.toList.traverse(resolveModuleIds).map(_.toMap).await
        // )

        val dirBasedGroups: Map[GroupId, List[ModuleId]] = rootDir.listSubdirs
          .evalMap { d =>
            async[IO] {
              val groupId = GroupId(d.fileName.toString)
              val modules = resolveModuleIds(groupId, d).await
              (groupId, modules)
            }
          }
          .compile
          .to(Map)
          .await

        // Simulate the traverse part
        val otherGroupIds = List(GroupId("other1"), GroupId("other2"))
        val otherGroups: Map[GroupId, List[ModuleId]] = otherGroupIds
          .traverse { gid =>
            // Note: these paths don't exist, so will return empty lists
            resolveModuleIds(gid, rootDir / gid.id)
              .handleError(_ => List.empty)
              .map(modules => (gid, modules))
          }
          .map(_.toMap)
          .await

        val allGroups = dirBasedGroups ++ otherGroups

        assert(dirBasedGroups.size == 3, s"Expected 3 dir-based groups")
        GroupsAndModuleIds(allGroups)
      }
    }
  }

  // Higher-order function that returns a closure
  def createDelayedProcessor[A, B](transform: A => B): A => () => B = {
    a => () => transform(a)
  }

  // Higher-order function that takes a function and applies it with await
  def applyWithTransform[A, B](items: List[A])(fn: A => B): List[B] = {
    items.map(fn)
  }

  // Higher-order function that takes a function and returns a closure
  // The closure will also use another HO function with the argument
  def processAndDelay[A, B, C](processor: A => B)(finalizer: B => C): () => C = {
    () => {
      val intermediate = processor(1.asInstanceOf[A])
      finalizer(intermediate)
    }
  }

  // Another HO function that returns a closure containing HO function calls
  def createNestedProcessor[A](innerFn: Int => A): () => List[A] = {
    () => {
      // This closure calls another HO function with the argument
      applyWithTransform(List(1, 2, 3))(innerFn)
    }
  }

  test("closure with captured await context - executed outside async block") {
    withTempDirectoryStructure { rootDir =>
      // Create a closure inside async block that captures await context
      val closureIO: IO[() => IO[Int]] = async[IO] {
        val inputValue = IO.delay(42).await

        // Return a closure that uses await when executed
        () => async[IO] {
          // This closure uses await inside a HO function
          // When executed outside the original async block, it creates a new async context
          val result = applyWithTransform(List(1, 2, 3)) { x =>
            IO.delay(x * 2).await  // await inside HO function
          }
          result.sum + inputValue
        }
      }

      // Now try to execute the closure OUTSIDE the async block
      // This should trigger "Dispatcher already closed" if the dispatcher
      // lifetime is tied to the async block
      closureIO.flatMap { thunk =>
        thunk()  // Execute the closure
      }.map { result =>
        assert(result == 54, s"Expected 54 but got $result")  // 2+4+6+42 = 54
      }
    }
  }

  test("nested closures with multiple await contexts") {
    withTempDirectoryStructure { rootDir =>
      // Create nested closures that capture await contexts
      val closureIO: IO[() => IO[String]] = async[IO] {
        val dirs = rootDir.listSubdirs.compile.toList.await

        // Return a closure that will use await when executed
        () => async[IO] {
          // This async block is executed outside the original async context
          val names = applyWithTransform(dirs) { path =>
            IO.delay(path.fileName.toString).await  // await in HO function
          }
          names.mkString(",")
        }
      }

      // Execute the closure outside the async block
      closureIO.flatMap { thunk =>
        thunk()  // This might fail with "Dispatcher already closed"
      }.map { result =>
        assert(result.contains("group"), s"Expected group names but got: $result")
      }
    }
  }

  test("closure returned from evalMap - dispatcher lifetime issue") {
    withTempDirectoryStructure { rootDir =>
      // This test specifically targets the pattern from the issue:
      // Using evalMap where the lambda returns a closure that captures await context

      val closuresIO: IO[List[() => IO[Int]]] = async[IO] {
        val closures = rootDir.listSubdirs
          .evalMap { dir =>
            async[IO] {
              // Return a closure from inside evalMap's async block
              () => async[IO] {
                // This closure uses await inside a HO function
                val files = applyWithTransform(List(dir)) { path =>
                  Files[IO]
                    .list(path)
                    .compile
                    .toList
                    .await  // await inside HO function in the closure
                }
                files.flatten.size
              }
            }
          }
          .compile
          .toList
          .await

        closures
      }

      // Now try to execute all the closures OUTSIDE the async block
      closuresIO.flatMap { thunks =>
        thunks.traverse { thunk =>
          thunk()  // Execute each closure - may fail with "Dispatcher already closed"
        }
      }.map { results =>
        assert(results.size == 3, s"Expected 3 results but got ${results.size}")
      }
    }
  }

  test("deeply nested HO functions with await - closure escaping async") {
    withTempDirectoryStructure { rootDir =>
      // Create a closure with nested HO functions that captures the async context
      val closureIO: IO[() => IO[Int]] = async[IO] {
        val count = rootDir.listSubdirs.compile.toList.await.size

        // Return a closure that will need the dispatcher when executed
        () => async[IO] {
          // This async block is created inside the closure
          // When the closure is executed outside the original async block,
          // it may try to use a closed dispatcher
          val result = applyWithTransform(List(1, 2, 3)) { n =>
            IO.delay(n + count).await
          }
          result.sum
        }
      }

      // Execute the closure OUTSIDE the async block
      closureIO.flatMap { thunk =>
        thunk()  // This might fail with "Dispatcher already closed"
      }.map { result =>
        assert(result > 0, s"Expected positive result but got $result")
      }
    }
  }

  // NOTE: Tests that reproduce the "Dispatcher already closed" issue have been
  // moved to DispatcherClosureEscapeTest.scala for isolated debugging
}
