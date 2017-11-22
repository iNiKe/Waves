package com.wavesplatform.network

import java.util.concurrent.TimeUnit

import cats.implicits._
import com.google.common.cache.CacheBuilder
import io.netty.channel._
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import monix.reactive.Observable
import scorex.transaction.History.BlockchainScore
import scorex.utils.ScorexLogging

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

object RxScoreObserver extends ScorexLogging {

  case class BestChannel(channel: Channel, score: BlockchainScore)

  type SyncWith = Option[BestChannel]

  def apply(scoreTtl: FiniteDuration, localScores: Observable[BlockchainScore],
            remoteScores: Observable[(Channel, BlockchainScore)],
            channelClosed: Observable[Channel]): Observable[SyncWith] = {

    val scheduler: SchedulerService = Scheduler.singleThread("rx-score-observer")

    var localScore: BlockchainScore = 0
    var currentBestChannel: Option[Channel] = None
    val scores = CacheBuilder.newBuilder()
      .expireAfterWrite(scoreTtl.toMillis, TimeUnit.MILLISECONDS)
      .build[Channel, BlockchainScore]()

    def newBestChannel(): Observable[SyncWith] = {
      val betterChannels = scores.asMap().asScala.filter(_._2 > localScore)
      if (betterChannels.isEmpty) {
        log.debug("No better scores of remote peers, sync complete")
        currentBestChannel = None
        Observable(None)
      } else {
        val groupedByScore = betterChannels.toList.groupBy(_._2)
        val bestScore = groupedByScore.keySet.max
        val bestChannels = groupedByScore(bestScore).map(_._1)
        currentBestChannel match {
          case Some(c) if bestChannels contains c =>
            log.trace("Best channel not changed")
            Observable.empty
          case _ =>
            val head = bestChannels.head
            currentBestChannel = Some(head)
            log.trace(s"New best channel score=$bestScore > localScore $localScore")
            Observable(Some(BestChannel(head, bestScore)))
        }
      }
    }

    val x = localScores.executeOn(scheduler).mapTask(newLocalScore => Task {
      log.debug(s"New local score = $newLocalScore observed")
      localScore = newLocalScore
    })

    val y = channelClosed.executeOn(scheduler).mapTask(ch => Task {
      scores.invalidate(ch)
      if (currentBestChannel.contains(ch))
        log.debug(s"Best channel $ch has been closed")

      currentBestChannel = None
    })

    val z = remoteScores.executeOn(scheduler).mapTask { case ((ch, score)) => Task {
      scores.put(ch, score)
      log.trace(s"${id(ch)} New score $score")
    }
    }

    Observable.merge(x, y, z).flatMap(_ => newBestChannel())
  }
}