package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime

import akka.actor.{Actor, Props}
import net.ripe.rpki.publicationserver.model.{Delta, ServerState}
import net.ripe.rpki.publicationserver.store.DeltaStore
import net.ripe.rpki.publicationserver.{Logging, Urls}
import com.softwaremill.macwire.MacwireMacros._

case class CleanCommand(newServerState: ServerState, deltas: Seq[Delta])
case class CleanSnapshotsCommand(snapshotTimestamp: FileTime)

class DeltaCleanActor extends Actor with Logging with Urls {

  private val deltaStore = DeltaStore.get

  private val repositoryWriter = wire[RepositoryWriter]

  override def receive = {
    case CleanCommand(newServerState, deltas) =>
      logger.info("Removing deltas from DB and filesystem")
      deltaStore.delete(deltas)
      repositoryWriter.deleteDeltas(conf.locationRepositoryPath, deltas)
    case CleanSnapshotsCommand(timestamp) =>
      logger.info(s"Removing snapshots older than $timestamp")
      repositoryWriter.deleteSnapshotsOlderThan(conf.locationRepositoryPath, timestamp)
  }
}

object DeltaCleanActor {
  def props = Props(new DeltaCleanActor())
}
