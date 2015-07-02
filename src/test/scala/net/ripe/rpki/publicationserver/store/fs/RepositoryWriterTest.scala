package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths}

import net.ripe.rpki.publicationserver.PublicationServerBaseTest

import scala.util.Random

class RepositoryWriterTest extends PublicationServerBaseTest {

  val subject = new RepositoryWriter

  val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test")

  test("should delete old snapshots") {
    val timestamp = System.currentTimeMillis()
    val repoFiles = setupTestRepo(timestamp)
    val deleteTimestamp = timestamp - 5000
    val (toDelete, toKeep) = repoFiles.partition(f => f.endsWith("snapshot.xml") && mtimeIsBefore(f, deleteTimestamp))
    assume(toDelete.nonEmpty)
    assume(toKeep.nonEmpty)

    subject.deleteSnapshotsOlderThan(rootDir.toString, FileTime.fromMillis(deleteTimestamp))

    toDelete.filter(Files.exists(_)) shouldBe empty
    toKeep.filter(Files.notExists(_)) shouldBe empty
  }

  def setupTestRepo(timestamp: Long): Seq[Path] = {
    (for {
      sessionDir <- (1 to 10).map(_ => Files.createTempDirectory(rootDir, "session"))
      serialDir <- (1 to 10).map(_ => Files.createTempDirectory(sessionDir, "serial"))
    } yield {
      val fileTime: FileTime = FileTime.fromMillis(timestamp - Random.nextInt(10 * 1000))
      val delta = Files.createFile(serialDir.resolve("delta.xml"))
      Files.setLastModifiedTime(delta, fileTime)
      val snapshot = Files.createFile(serialDir.resolve("snapshot.xml"))
      Files.setLastModifiedTime(snapshot, fileTime)
      Seq(delta, snapshot)
    }).flatten :+ Files.createFile(rootDir.resolve("notification.xml"))
  }

  def mtimeIsBefore(f: Path, deleteTimestamp: Long): Boolean = {
    Files.getLastModifiedTime(f).toMillis <= deleteTimestamp
  }
}
