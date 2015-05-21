package net.ripe.rpki.publicationserver

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import com.google.common.io.BaseEncoding

import scala.xml.{Elem, Node}

class SnapshotState(val sessionId: SessionId, val serial: BigInt, val pdus: SnapshotState.SnapshotMap) {

  def apply(queries: Seq[QueryPdu]): Either[MsgError, SnapshotState] = {

    val newPdus = queries.foldLeft[Either[MsgError, SnapshotState.SnapshotMap]](Right(pdus)) { (pduMap, query) =>
      pduMap.right.flatMap { m =>
        query match {
          case PublishQ(uri, _, None, base64) =>
            m.get(uri) match {
              case Some((_, h)) =>
                Left(MsgError(MsgError.HashForInsert, s"Inserting and existing object [$uri]"))
              case None =>
                Right(m + (uri -> (base64, SnapshotState.hash(base64))))
            }

          case PublishQ(uri, _, Some(qHash), base64) =>
            m.get(uri) match {
              case Some((_, h)) =>
                if (h == Hash(qHash))
                  Right(m + (uri -> (base64, SnapshotState.hash(base64))))
                else
                  Left(MsgError(MsgError.NonMatchingHash, s"Cannot republish the object [$uri], hash doesn't match"))

              case None =>
                Left(MsgError(MsgError.NoHashForUpdate, s"No hash provided for updating the object [$uri]"))
            }

          case WithdrawQ(uri, _, qHash) =>
            m.get(uri) match {
              case Some((_, h)) =>
                if (h == Hash(qHash))
                  Right(m - uri)
                else
                  Left(MsgError(MsgError.NonMatchingHash, s"Cannot withdraw the object [$uri], hash doesn't match"))

              case None =>
                Left(MsgError(MsgError.NoHashForWithdraw, s"No hash provided for withdrawing the object [$uri]"))
            }
        }
      }
    }

    newPdus.right.map(new SnapshotState(sessionId, serial + 1, _))
  }

}

trait Hashing {
  private val base64 = BaseEncoding.base64()

  def stringify(bytes: Array[Byte]) = Option(bytes).map {
    _.map { b => String.format("%02X", new Integer(b & 0xff)) }.mkString
  }.getOrElse("")

  def hash(b64: Base64) = {
    val Base64(b64String) = b64
    val bytes = base64.decode(b64String)
    val digest = MessageDigest.getInstance("SHA-256")
    Hash(stringify(digest.digest(bytes)))
  }
}

object SnapshotState extends Hashing {

  type SnapshotMap = Map[String, (Base64, Hash)]

  private val state = new AtomicReference[SnapshotState]()

  def get = state.get()

  def transform(t: SnapshotState => SnapshotState): SnapshotState = {
    var currentState: SnapshotState = null
    var newState: SnapshotState = null
    do {
      currentState = state.get
      newState = t(currentState)
    }
    while (!state.compareAndSet(currentState, newState))
    newState
  }

  def serialize(state: SnapshotState) = snapshotXml (
    state.sessionId,
    state.serial,
    state.pdus.map { e =>
      val (uri, (base64, hash)) = e
      <publish uri={uri} hash={hash.hash}>{base64.value}</publish>
    }
  )

  private def snapshotXml(sessionId: SessionId, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <snapshot xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.id} serial={serial.toString()}>
      {pdus}
    </snapshot>
}


