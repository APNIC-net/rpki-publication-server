package net.ripe.rpki.publicationserver

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, ServerState}

trait Urls {
  lazy val conf = wire[ConfigWrapper]

  lazy val repositoryUri = conf.locationRepositoryUri

  def snapshotUrl(serverState: ServerState) = {
    val ServerState(sessionId, serial) = serverState
    repositoryUri + "/" + sessionId + "/" + serial + "/snapshot.xml"
  }

  def deltaUrl(delta: Delta) = repositoryUri + "/" + delta.sessionId + "/" + delta.serial + "/delta.xml"
}
