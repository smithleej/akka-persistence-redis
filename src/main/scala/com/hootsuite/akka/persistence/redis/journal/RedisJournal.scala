package com.hootsuite.akka.persistence.redis.journal

import akka.actor.ActorLogging
import akka.persistence._
import akka.persistence.journal.AsyncWriteJournal
import com.hootsuite.akka.persistence.redis.{ByteArraySerializer, DefaultRedisComponent}
import redis.api.Limit

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Failure, Success}

/**
 * Writes journals in Sorted Set, using SequenceNr as score.
 * Deprecated API's are not implemented which causes few TCK tests to fail.
 */
class RedisJournal extends AsyncWriteJournal with ActorLogging with DefaultRedisComponent with ByteArraySerializer with JournalExecutionContext {

  /**
   * Define actor system for Rediscala and ByteArraySerializer
   */
  override implicit lazy val actorSystem = context.system

  // Redis key namespace for journals
  private def journalKey(persistenceId: String) = s"journal:$persistenceId"

  /**
   * Plugin API: synchronously writes a batch of persistent messages to the journal.
   * The batch write must be atomic i.e. either all persistent messages in the batch
   * are written or none.
   */
  def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = {

      val transaction = redis.transaction()

      val res = messages.map { ar =>
        ar.payload.foreach { pr =>
          toBytes(pr) match {
            case Success(serialized) =>
              val journal = Journal(pr.sequenceNr, serialized, pr.deleted)
              transaction.zadd(journalKey(pr.persistenceId), (pr.sequenceNr, journal))
            case Failure(e) => Future.failed(throw new scala.RuntimeException("writeMessages: failed to write PersistentRepr to redis"))
          }
        }
        Success()
      }

      transaction.exec().map(_ => res)
  }

  /**
   * Plugin API: synchronously deletes all persistent messages up to `toSequenceNr`
   * (inclusive). If `permanent` is set to `false`, the persistent messages are marked
   * as deleted, otherwise they are permanently deleted.
   */
  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    redis.zremrangebyscore(journalKey(persistenceId), Limit(-1), Limit(toSequenceNr)).map(long => ())
  }

  /**
   * Plugin API: asynchronously replays persistent messages. Implementations replay
   * a message by calling `replayCallback`. The returned future must be completed
   * when all messages (matching the sequence number bounds) have been replayed.
   * The future must be completed with a failure if any of the persistent messages
   * could not be replayed.
   *
   * The `replayCallback` must also be called with messages that have been marked
   * as deleted. In this case a replayed message's `deleted` method must return
   * `true`.
   *
   * The channel ids of delivery confirmations that are available for a replayed
   * message must be contained in that message's `confirms` sequence.
   *
   * @param persistenceId persistent actor id.
   * @param fromSequenceNr sequence number where replay should start (inclusive).
   * @param toSequenceNr sequence number where replay should end (inclusive).
   * @param max maximum number of messages to be replayed.
   * @param replayCallback called to replay a single message. Can be called from any
   *                       thread.
   *
   */
  def asyncReplayMessages(persistenceId : String, fromSequenceNr : Long, toSequenceNr : Long, max : Long)
    (replayCallback : PersistentRepr => Unit) : Future[Unit] = {

    import Journal._

    for {
      journals <- redis.zrangebyscore(journalKey(persistenceId), Limit(fromSequenceNr), Limit(toSequenceNr), Some((0L, max)))
    } yield {
      journals.foreach { journal =>
        fromBytes[PersistentRepr](journal.persistentRepr) match {
          case Success(pr) => replayCallback(pr)
          case Failure(e) => Future.failed(throw new RuntimeException("asyncReplayMessages: Failed to deserialize PersistentRepr"))
        }
      }
    }
  }

  /**
   * Plugin API: asynchronously reads the highest stored sequence number for the
   * given `persistenceId`.
   *
   * @param persistenceId persistent actor id.
   * @param fromSequenceNr hint where to start searching for the highest sequence
   *                       number.
   */
  def asyncReadHighestSequenceNr(persistenceId : String, fromSequenceNr : Long) : Future[Long] = {
    import Journal._

    redis.zrevrangebyscoreWithscores(journalKey(persistenceId), Limit(Double.MaxValue), Limit(fromSequenceNr), Some(0L, 1L)).map{
      journals => journals.headOption.map{ a => a._2.toLong }.getOrElse(0L)
    }
  }
}

trait JournalExecutionContext {
  // Global ExecutionContext is provided to Rediscala for non-blocking asynchronous Redis operations.
  // Be cautioned that it can be blocked for synchronous APIs.
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val ec: ExecutionContext = global
}
