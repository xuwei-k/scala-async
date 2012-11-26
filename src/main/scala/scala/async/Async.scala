/*
 * Copyright (C) 2012 Typesafe Inc. <http://www.typesafe.com>
 */

package scala.async

import scala.language.experimental.macros
import scala.reflect.macros.Context

/*
 * @author Philipp Haller
 */
object Async extends AsyncBase {
  import scala.concurrent.Future

  lazy val futureSystem = ScalaConcurrentFutureSystem
  type FS = ScalaConcurrentFutureSystem.type

  def async[T](body: T) = macro asyncImpl[T]

  override def asyncImpl[T: c.WeakTypeTag](c: Context)(body: c.Expr[T]): c.Expr[Future[T]] = super.asyncImpl[T](c)(body)
}

object AsyncId extends AsyncBase {
  lazy val futureSystem = IdentityFutureSystem
  type FS = IdentityFutureSystem.type

  def async[T](body: T) = macro asyncImpl[T]

  override def asyncImpl[T: c.WeakTypeTag](c: Context)(body: c.Expr[T]): c.Expr[T] = super.asyncImpl[T](c)(body)
}

/**
 * A base class for the `async` macro. Subclasses must provide:
 *
 * - Concrete types for a given future system
 * - Tree manipulations to create and complete the equivalent of Future and Promise
 * in that system.
 * - The `async` macro declaration itself, and a forwarder for the macro implementation.
 * (The latter is temporarily needed to workaround bug SI-6650 in the macro system)
 *
 * The default implementation, [[scala.async.Async]], binds the macro to `scala.concurrent._`.
 */
abstract class AsyncBase {
  self =>

  type FS <: FutureSystem
  val futureSystem: FS

  /**
   * A call to `await` must be nested in an enclosing `async` block.
   *
   * A call to `await` does not block the current thread, rather it is a delimiter
   * used by the enclosing `async` macro. Code following the `await`
   * call is executed asynchronously, when the argument of `await` has been completed.
   *
   * @param awaitable the future from which a value is awaited.
   * @tparam T        the type of that value.
   * @return          the value.
   */
  // TODO Replace with `@compileTimeOnly when this is implemented SI-6539
  @deprecated("`await` must be enclosed in an `async` block", "0.1")
  def await[T](awaitable: futureSystem.Fut[T]): T = ???

  def asyncImpl[T: c.WeakTypeTag](c: Context)(body: c.Expr[T]): c.Expr[futureSystem.Fut[T]] = {
    import c.universe._

    val builder = ExprBuilder[c.type, futureSystem.type](c, self.futureSystem)
    val anaylzer = AsyncAnalysis[c.type](c)
    val utils = TransformUtils[c.type](c)
    import utils.{name, defn}
    import builder.futureSystemOps

    anaylzer.reportUnsupportedAwaits(body.tree)

    // Transform to A-normal form:
    //  - no await calls in qualifiers or arguments,
    //  - if/match only used in statement position.
    val anfTree: Block = {
      val anf = AnfTransform[c.type](c)
      val stats1 :+ expr1 = anf(body.tree)
      val block = Block(stats1, expr1)
      c.typeCheck(block).asInstanceOf[Block]
    }

    // Analyze the block to find locals that will be accessed from multiple
    // states of our generated state machine, e.g. a value assigned before
    // an `await` and read afterwards.
    val renameMap: Map[Symbol, TermName] = {
      anaylzer.valDefsUsedInSubsequentStates(anfTree).map {
        vd =>
          (vd.symbol, name.fresh(vd.name))
      }.toMap
    }

    val asyncBlock: builder.AsyncBlock = builder.build(anfTree, renameMap)
    import asyncBlock.asyncStates
    logDiagnostics(c)(anfTree, asyncStates.map(_.toString))

    val localVarTrees = anfTree.collect {
      case vd@ValDef(_, _, tpt, _) if renameMap contains vd.symbol =>
        utils.mkVarDefTree(tpt.tpe, renameMap(vd.symbol))
    }

    val onCompleteHandler = asyncBlock.onCompleteHandler
    val resumeFunTree = asyncBlock.resumeFunTree[T]

    val prom: Expr[futureSystem.Prom[T]] = reify {
      // Create the empty promise
      val result$async = futureSystemOps.createProm[T].splice
      // Initialize the state
      var state$async = 0
      // Resolve the execution context
      val execContext$async = futureSystemOps.execContext.splice
      var onCompleteHandler$async: util.Try[Any] => Unit = null

      // Spawn a future to:
      futureSystemOps.future[Unit] {
        c.Expr[Unit](Block(
          // define vars for all intermediate results that are accessed from multiple states
          localVarTrees :+
            // define the resume() method
            resumeFunTree :+
            // assign onComplete function. (The var breaks the circular dependency with resume)`
            Assign(Ident(name.onCompleteHandler), onCompleteHandler),
          // and get things started by calling resume()
          Apply(Ident(name.resume), Nil)))
      }(c.Expr[futureSystem.ExecContext](Ident(name.execContext))).splice
      // Return the promise from this reify block...
      result$async
    }
    // ... and return its Future from the macro.
    val result = futureSystemOps.promiseToFuture(prom)

    AsyncUtils.vprintln(s"async state machine transform expands to:\n ${result.tree}")

    result
  }

  def logDiagnostics(c: Context)(anfTree: c.Tree, states: Seq[String]) {
    def location = try {
      c.macroApplication.pos.source.path
    } catch {
      case _: UnsupportedOperationException =>
        c.macroApplication.pos.toString
    }

    AsyncUtils.vprintln(s"In file '$location':")
    AsyncUtils.vprintln(s"${c.macroApplication}")
    AsyncUtils.vprintln(s"ANF transform expands to:\n $anfTree")
    states foreach (s => AsyncUtils.vprintln(s))
  }
}