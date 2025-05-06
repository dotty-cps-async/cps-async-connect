package cps.monads.catsEffect
/*
 * (C) Ruslan Shevchenko <ruslan@shevchenko.kiev.ua>
 * 2021
 */

import cats.*
import cats.effect.*
import cats.effect.kernel.*

import cps.*


 /***
 * Pseudo-synchronious syntax for resource, which can be used in async block.
 *
 * Usage:
 *  assuming we have:
 * ```
 *     def open(file: File): Resource[IO, BufferedReader]
 * ```
 * we can 
 * ```
 *     async[IO] {
 *        ....  
 *        open(file).useOn{ buffer =>
 *            await(doSomething)
 *            buffer.write(r)
 *            result
 *        }
 *     }
 * ```
 **/
 extension [F[_],A](r: Resource[F,A])(using m:CpsMonad[F], cm: MonadCancel[F,Throwable]) 

    //  bug or undefined specs in dotty: we can't name extension if we have method with the same name
    transparent inline def useOn[B](inline f: A=>B)(using CpsMonadContext[F]): B = 
        await(r.use(a => m.pure(f(a)) ))


/***
 * Pseudo-synchronious syntax for resource, which can be used in async block.
 *
 * ```
 *     async[IO] {
 *        ....  
 *        Resource.using(openFile){ buffer =>
 *            await(doSomething)
 *            buffer.write(r)
 *            result
 *        }
 *     }
 * ```
 **/
extension (resourceSingleton: Resource.type)

     transparent inline def using[F[_],A, B](r:Resource[F,A])(inline f: A=>B)(using m:CpsMonad[F], cm: MonadCancel[F,Throwable], mc:CpsMonadContext[F]): B =
          await(r.use(a => m.pure(f(a)) ))


     transparent inline def using[F[_],A1, A2, B](r1:Resource[F,A1], r2:Resource[F,A2])(inline f: (A1,A2)=>B)(using m:CpsMonad[F], cm: MonadCancel[F,Throwable], mc: CpsMonadContext[F]): B =
          await(r1.use(a1 => r2.use(a2 => m.pure(f(a1,a2)))))


     transparent inline def using[F[_],A1, A2, A3, B](r1:Resource[F,A1], r2:Resource[F,A2], r3: Resource[F,A3])(inline f: (A1,A2,A3)=>B)(using m:CpsMonad[F], cm: MonadCancel[F,Throwable], mc: CpsMonadContext[F]): B =
          await(r1.use(a1 => r2.use(a2 => r3.use(a3 => m.pure(f(a1,a2,a3))))))


