package edu.illinois.ncsa.fence.utils

import com.twitter.util.{Future => TwitterFuture, Promise => TwitterPromise, Return, Throw}
import scala.concurrent.{Future => ScalaFuture, Promise => ScalaPromise, ExecutionContext}
import scala.util.{Success, Failure}

/**
  * Convert between Twitter Futures and Scala Futures. See:
  * https://twitter.github.io/util/guide/util-cookbook/futures.html
  */
object TwitterFutures {

  /** Convert from a Twitter Future to a Scala Future */
  implicit class RichTwitterFuture[A](val tf: TwitterFuture[A]) extends AnyVal {
    def asScala(implicit e: ExecutionContext): ScalaFuture[A] = {
      val promise: ScalaPromise[A] = ScalaPromise()
      tf.respond {
        case Return(value) => promise.success(value)
        case Throw(exception) => promise.failure(exception)
      }
      promise.future
    }
  }

  /** Convert from a Scala Future to a Twitter Future */
  implicit class RichScalaFuture[A](val sf: ScalaFuture[A]) extends AnyVal {
    def asTwitter(implicit e: ExecutionContext): TwitterFuture[A] = {
      val promise: TwitterPromise[A] = new TwitterPromise[A]()
      sf.onComplete {
        case Success(value) => promise.setValue(value)
        case Failure(exception) => promise.setException(exception)
      }
      promise
    }
  }
}
