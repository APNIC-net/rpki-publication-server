package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.{ServerStateStore, DeltaStore, ObjectStore}
import net.ripe.rpki.publicationserver.{PublicationServiceActor, Config, Logging}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Try, Failure, Success}


case class InitCommand(newServerState: ServerState)
case class WriteCommand(newServerState: ServerState)
case class CleanSnapshotsCommand(timestamp: FileTime, latestSerial: Long)

case class UpdateSnapsot()
case class SetTarget(actor: ActorRef)

class Throttler extends Actor with Logging with Config {

  import context._

  var target: ActorRef = _

  var scheduled = false

  override def receive = {
    case SetTarget(t) =>
      target = t
      scheduled = false

    case UpdateSnapsot =>
      if (!scheduled) {
        system.scheduler.scheduleOnce(10.seconds, target, UpdateSnapsot())
        scheduled = true
      }
  }
}


class FSWriterActor extends Actor with Logging with Config {
  import context._

  val rrdpWriter = wire[RrdpRepositoryWriter]
  lazy val rsyncWriter = wire[RsyncRepositoryWriter]

  protected val deltaStore = DeltaStore.get

  protected val objectStore = ObjectStore.get

  protected val serverStateStore = ServerStateStore.get

  private var throttler: ActorRef = _

  override def preStart() = {
    throttler = system.actorOf(Props(new Throttler), "snapshot-writing-throttler")
    throttler ! SetTarget(self)
  }

  override def receive = {
    case InitCommand(newServerState) =>
      Try(initFSContent(newServerState)).recover { case e =>
        logger.error("Error processing command", e)
      }.get

    case CleanSnapshotsCommand(timestamp, latestSerial) =>
      tryProcess(cleanupSnapshots(timestamp, latestSerial))

    case WriteCommand(newServerState) =>
      tryProcess(updateFSContent(newServerState))

    case UpdateSnapsot =>
      tryProcess(updateFSSnapshot())
  }

  def tryProcess[T](f : => T) = Try(f).failed.foreach {
    logger.error("Error processing command", _)
  }

  def initFSContent(newServerState: ServerState): Unit = {
    val objects = objectStore.listAll
    val snapshot = Snapshot(newServerState, objects)

    val rsync = Future {
      try rsyncWriter.writeSnapshot(snapshot) catch {
        case e: Throwable =>
          logger.error(s"Error occurred while synching rsync repository", e)
      }
    }

    try {
      val deltas = deltaStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.unpublishedFileRetainPeriod)
      val (deltasToPublish, deltasToDelete) = deltas.partition(_.whenToDelete.isEmpty)
      val newNotification = Notification.create(snapshot, newServerState, deltasToPublish.toSeq)

      val failures = deltasToPublish.par.map { d =>
        (d, rrdpWriter.writeDelta(conf.rrdpRepositoryPath, d))
      }.collect {
        case (d, Failure(f)) => (d, f)
      }.seq

      if (failures.isEmpty) {
        val now = System.currentTimeMillis
        rrdpWriter.writeNewState(conf.rrdpRepositoryPath, newServerState, newNotification, snapshot) match {
          case Success(timestampOption) =>
            logger.info(s"Written snapshot ${newServerState.serialNumber}")
            if (timestampOption.isDefined) scheduleSnapshotCleanup(timestampOption.get, newServerState.serialNumber)
            cleanupDeltas(deltasToDelete.filter(_.whenToDelete.exists(_.getTime < now)))
          case Failure(e) =>
            logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
        }
      } else {
        failures.foreach { x =>
          val (d, f) = x
          logger.info(s"Error occurred while writing a delta ${d.serial}: $f")
        }
      }
    } finally {
      Await.result(rsync, 10.minutes)
    }
  }

  def updateFSContent(newServerState: ServerState): Unit = {
    val givenSerial = newServerState.serialNumber

    updateFSDelta(givenSerial)

    throttler ! UpdateSnapsot()
  }

  def updateFSDelta(givenSerial: Long): Unit = {
    deltaStore.getDelta(givenSerial) match {
      case None =>
        logger.error(s"Could not find delta $givenSerial")
      case Some(delta) =>
        logger.debug(s"Writing delta $givenSerial to rsync filesystem")
        rsyncWriter.writeDelta(delta).recover {
          case e: Exception =>
            logger.error(s"Could not write delta $givenSerial to rsync repo: ", e)
        }
        logger.debug(s"Writing delta $givenSerial to RRDP filesystem")
        rrdpWriter.writeDelta(conf.rrdpRepositoryPath, delta).recover {
          case e: Exception =>
            logger.error(s"Could not write delta $givenSerial to RRDP repo: ", e)
        }
    }
  }


  def updateFSSnapshot(): Unit = {
    val serverState = serverStateStore.get
    val objects = objectStore.listAll

    logger.info(s"Writing snapshot ${serverState.serialNumber} to filesystem")
    val snapshot = Snapshot(serverState, objects)

    val deltas = deltaStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.unpublishedFileRetainPeriod)

    val (deltasToPublish, deltasToDelete) = deltas.partition(_.whenToDelete.isEmpty)

    val newNotification = Notification.create(snapshot, serverState, deltasToPublish.toSeq)
    val now = System.currentTimeMillis
    rrdpWriter.writeNewState(conf.rrdpRepositoryPath, serverState, newNotification, snapshot) match {
      case Success(timestampOption) =>
        if (timestampOption.isDefined) scheduleSnapshotCleanup(timestampOption.get, serverState.serialNumber)
        cleanupDeltas(deltasToDelete.filter(_.whenToDelete.exists(_.getTime < now)))
      case Failure(e) =>
        logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
    }
  }

  def scheduleSnapshotCleanup(timestamp: FileTime, latestSerial: Long): Unit = {
    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod, self, CleanSnapshotsCommand(timestamp, latestSerial))
  }

  def cleanupSnapshots(timestamp: FileTime, latestSerial: Long): Unit = {
    logger.info(s"Removing snapshots older than $timestamp")
    rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, timestamp, latestSerial)
  }

  def cleanupDeltas(deltasToDelete: Iterable[Delta]): Unit = {
    if (deltasToDelete.nonEmpty) {
      logger.info("Removing deltas: " + deltasToDelete.map(_.serial).mkString(","))
      rrdpWriter.deleteDeltas(conf.rrdpRepositoryPath, deltasToDelete)
      deltaStore.delete(deltasToDelete)
    }
  }
}

object FSWriterActor {
  def props() = Props(new FSWriterActor())
}
