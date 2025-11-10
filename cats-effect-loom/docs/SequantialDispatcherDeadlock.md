Sequential Dispatcher causes **thread pool exhaustion deadlock** when closures with `await` escape and execute synchronously:

```scala
// This pattern causes deadlock with Sequential Dispatcher:
val closureIO: IO[() => Int] = async[IO] {
  val processor = (x: Int) => {
    List(1, 2, 3).map { i =>
      IO.pure(i * x).await  // Multiple concurrent awaits
    }
  }
  processor
}

closureIO.flatMap { thunk =>
  IO.delay(thunk(5))  // Deadlock! All io-compute threads blocked
}
```

**What happens:**
1. Each `await` blocks an io-compute thread waiting for Sequential Dispatcher
2. Sequential Dispatcher runs on the same io-compute pool
3. All threads become blocked → no thread available to execute work → **deadlock**

**Parallel Dispatcher avoids this** because it executes operations concurrently, so virtual threads can block without exhausting the thread pool.

