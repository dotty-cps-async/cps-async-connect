

 This is a helper connect objects for providing [dotty-cps-async](https://github.com/rssh/dotty-cps-async) CpsAsyncMonad typeclasses for common effect stacks and streaming libraries.


## cats-effects:

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-cats-effect" % version  
```

And if you want to use JDK-21 virtual threads for translation of high-order function arguments:

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-cats-effect-loom" % version  
```


Usage:

```scala
import cps._
import cps.monads.catsEffect.given

...
def doSomething(): IO[T] = async[IO] {
   ...
}

```

 or import specific class to allow compiler to deduce given monad automatically.

  * IO  -  catsIO  (implements CpsConcurrentEffectMonad with conversion to Future)
  * Generic `F[_]:Async` - catsAsync (implements CpsAsyncMonad)
  * Generic `F[_]:MonadThrow` - catsMonadThrow (implements CpsTryMonad)
  * Generic `F[_]:MonadCancel` - catsMonadCancel (implements CpsTryMonad)
  * Generic `F[_]:Monad` - catsMonad (implements CpsMonad)

Also implemented pseudo-synchronious interface for resources, i.e. for `r:Resource[F,A]` it is possible to write:

```scala
async[F] {
  .......
  Resource.using(r1,r2){ file1, file2 =>
    val data = await(fetchData(url))
    file1.write(data)
    file1.write(s"data fetched from $url")
  }
} 
```

or

```scala
async[F] {
  ....
  r.useOn{file =>
     val data = await(fetchData())
     file.write(data)
  }
}
```

instead

```
 await(r.use{
    fetchData().map(data => f.write(data))
 })  
```

## Cancellation

`cps-async-connect-cats-effect` provides `CpsMonadCancel` typeclass, which implements finalising of resources during cancellation.

I.e. if we have next code:

```scala
async[IO] {
  val r = allocateResource()
  try {
    await(doSomething(r))
  } catch {
    case NonFatal(e) =>
      handleException(e)
  } finally {
    r.release()
  }
}
```
 and Fiber which evaluated this code will be canceled during `doSomething`,  then finally block will be executed.

Note, that execution model in this case will be differ from traditional java try-catch-finally:
 * it is impossible to catch CancellationException in the catch block.
 * during cancellation, exception from finalizers will not be propagated as outcome of cancelled Fiber, but instead 
      will be reported to the exception handler of the IO execution pool.
 * when finalizer is executed not during cancellation, but during normal completion, then exception will be propagated as outcome of the Fiber.

<!--
Next code is an example of handling 

-->


# monix:

```
  libraryDependencies += "com.github.rssh" %%% "cps-async-connect-monix" % version  
```

(or with '-lts' for scala-lts version)

Usage:

```scala
import cps.*
import cps.monads.monix.given
import monix.eval.Task

...
def doSomething(): Task[T] = async[Task] {
   ...
}

```

```scala
import cps.*
import monix.*
import monix.reactive.*
import cps.monads.monix.given
import cps.stream.monix.given

def intStream() = asyncStream[Observable[Int]] { out =>
    for(i <- 1 to N) {
       out.emit(i)
    }
}

```


## scalaz IO:

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-scalaz" % version  
```

  * IO - cps.monads.scalaz.scalazIO  (implements CpsTryMonad)


## zio:

for 1.x:

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-zio" % version 
```

for 2.x:

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-zio2" % version
```

and for loom support on JDK21+ :

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-zio2-loom" % version
```



Usage:

```scala
import cps.*
import cps.monads.zio.{given,*}

 val program = asyncRIO[R] {
    .....
 }

```

or for task:

```scala

 val program = async[Task] {
   ....
 }


```


  * ZIO  -  `asyncZIO[R,E]` as shortcat for `async[[X]=>>ZIO[R,E,X]]` (implements `CpsAsyncMonad` with conversion to `Future` if we have given `Runtime` in scope.)
  * RIO  -  use asyncRIO[R]  (implements CpsAsyncMonad with conversion)
  * Task  -  use async[Task]  (implements CpsAsyncMonad with conversion)
  * URIO  -  use asyncURIO[R]  (implements CpsMonad)
  
Also implement `using` pseudo-syntax for ZManaged: 

```
asyncRIO[R] {
  val managedResource = Managed.make(Queue.unbounded[Int])(_.shutdown)
  ZManaged.using(managedResource, secondResource) { queue =>
     doSomething()  // can use awaits inside.
  }

}
```

And generator syntax for ZStream:

```
val stream = asyncStream[Stream[Throwable,Int]] { out =>
       for(i <- 1 to N) {
         out.emit(i)
       }
}
```


## akka-streams


```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-akka-stream" % version  
```

Generator syntax for akka source.


## fs2 streams

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-fs2" % version
```

Generator syntax for fs2

## pekko-streams

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-pekko-stream" % version  
```

Generator syntax for pekko source.


## probability monad

```
  libraryDependencies += "io.github.dotty-cps-async" %%% "cps-async-connect-probability-monad" % version
```

CpsTryMonad instance for Distribution monad.



