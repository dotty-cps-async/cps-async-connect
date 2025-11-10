# Cats Effect Loom Integration for dotty-cps-async

This module enables `await` to work inside higher-order functions that don't have async variants, using JVM virtual threads (Project Loom) with Cats Effect `IO`.

```scala
import cats.effect.*
import cats.syntax.all.*
import cps.*
import cps.monads.catsEffect.given
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global

// WITHOUT this module:
def example = async[IO] {
  (Option(1), Option(2)).mapN { (a, b) =>
    IO.pure(a + b).await  //  Error: mapN doesn't have async variant
  }
}

// WITH this module:
def example = async[IO] {
  (Option(1), Option(2)).mapN { (a, b) =>
    IO.pure(a + b).await  // Works! Runtime provider executes on virtual thread
  }.getOrElse(0)
}

```

```scala
// Works with FS2 streams:
def processStream(stream: Stream[IO, User]): Stream[IO, Result] = 
  stream.evalMap { user =>
    async[IO] {
      val profile = fetchProfile(user.id).await
      val settings = fetchSettings(user.id).await
      combine(profile, settings)
    }
  }
```

**Note:** Many standard collections (List, Vector, etc.) have built-in async shift support and don't need this module. This is specifically for library functions without async variants.

See [dotty-cps-async Higher-Order Functions](https://dotty-cps-async.github.io/dotty-cps-async/HighOrderFunctions.html) for details on when async shifts are needed.

## Usage Patterns

There are two main patterns for using this integration, depending on your application's needs.

---

## Pattern 1: Global Dispatcher

**Use when:** You want zero boilerplate and don't need fine-grained control over Dispatcher lifetime.

### Setup

Import the global given instance:

```scala
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global
```

The Dispatcher is automatically created on first use with `IORuntime.global` and cleaned up on JVM shutdown.

### Example

```scala
import cats.effect.*
import cps.*
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global

object MyApp extends IOApp.Simple {
  def run: IO[Unit] = 
    ServiceA.processData()
}

// In another file - no setup needed!
object ServiceA {
  def processData(): IO[Unit] = async[IO] {
    val data = loadFromDatabase().await
    val processed = transformData(data).await
    saveResults(processed).await
  }
  
  def loadFromDatabase(): IO[Data] = ???
  def transformData(data: Data): IO[ProcessedData] = ???
  def saveResults(data: ProcessedData): IO[Unit] = ???
}
```

The global Dispatcher always uses Parallel mode.

---

## Pattern 2: Custom Dispatcher

**Use when:** You need explicit control over Dispatcher lifetime, want to use a specific `IORuntime`, or need different 
Dispatchers for different parts of your application.

### Setup with Resource

```scala
import cats.effect.*
import cps.*
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider

object MyApp extends IOApp.Simple {
  def run: IO[Unit] = 
    CpsCERuntimeAwaitProvider.resource[IO]().use { implicit provider =>
      // provider is available implicitly in this scope
      myApplication()
    }
  
  def myApplication()(using CpsCERuntimeAwaitProvider[IO]): IO[Unit] = 
    async[IO] {
      val result = computeSomething().await
      IO.println(s"Result: $result").await
    }
}
```

### Setup with Manual Dispatcher

```scala
import cats.effect.*
import cats.effect.std.Dispatcher
import cps.*
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider

object MyApp extends IOApp.Simple {
  def run: IO[Unit] = 
    Dispatcher.parallel[IO].use { dispatcher =>
      given CpsCERuntimeAwaitProvider[IO] = 
        CpsCERuntimeAwaitProvider.fromDispatcher[IO](dispatcher)
      
      myApplication()
    }
  
  def myApplication()(using CpsCERuntimeAwaitProvider[IO]): IO[Unit] = ???
}
```

**Note**: While you can create any Dispatcher type with `fromDispatcher`, only  Parallel Dispatcher is safe when closures with `await` escape async blocks. 
Sequential Dispatcher can cause thread pool exhaustion deadlocks.

### Custom Configuration

```scala
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider
import CpsCERuntimeAwaitProvider.DispatcherConfig

val config = DispatcherConfig(
  awaitOnShutdown = true  // wait for effects to complete on shutdown
)

CpsCERuntimeAwaitProvider.resource[IO](config).use { implicit provider =>
  myApplication()  // Uses Parallel Dispatcher (only safe mode)
}
```

### Propagating the Provider

When using custom Dispatcher, you need to propagate the provider through your application:

---

## How It Works

1. Each `async[IO]` block runs on a JVM virtual thread (Project Loom)
2. Dispatcher converts `IO` effects to `Future` so they can be awaited
3. The virtual thread blocks until the `IO` completes. Virtual threads make blocking cheap, so we can write direct-style code that looks synchronous but is actually async.

---

## Cleanup

**Global Dispatcher**: Automatically cleaned up on JVM shutdown via shutdown hook.

**Custom Dispatcher**: Cleaned up when the `Resource.use` block exits.

---

## Requirements

- Scala 3.3+
- JVM 21+ (for Virtual Threads / Project Loom)
- Cats Effect 3.5+
- dotty-cps-async 0.9.19+

---

## License

Same as dotty-cps-async project.
