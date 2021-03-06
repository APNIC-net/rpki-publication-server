package net.ripe.rpki.publicationserver.model

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.{Base64, Hash, PublicationServerBaseTest}

class SnapshotTest extends PublicationServerBaseTest {

  test("should serialize to proper xml") {
    val sessionId = UUID.randomUUID()
    val state = Snapshot(ServerState(sessionId, 123L), Seq((Base64("321"), new URI("rsync://bla"))))

    val xml = state.serialize.mkString

    trim(xml) should be(trim(s"""<snapshot version="1" session_id="$sessionId" serial="123" xmlns="http://www.ripe.net/rpki/rrdp">
                                  <publish uri="rsync://bla">321</publish>
                                </snapshot>"""))
  }
}
