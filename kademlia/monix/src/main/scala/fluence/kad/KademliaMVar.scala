/*
 * Copyright (C) 2017  Fluence Labs Limited
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package fluence.kad

import java.time.Instant

import cats.data.StateT
import cats.effect.{ExitCase, IO}
import cats.effect.concurrent.MVar
import cats.kernel.Monoid
import fluence.kad.protocol.{KademliaRpc, Key, Node}

import scala.language.implicitConversions

// TODO: write unit tests
object KademliaMVar {

  /**
   * Kademlia service to be launched as a singleton on local node.
   *
   * @param nodeId        Current node ID
   * @param contact       Node's contact to advertise
   * @param rpcForContact Getter for RPC calling of another nodes
   * @param conf          Kademlia conf
   * @param checkNode     Node could be saved to RoutingTable only if checker returns F[ true ]
   * @tparam C Contact info
   */
  def apply[F[_], C](
    nodeId: Key,
    contact: F[C],
    rpcForContact: C ⇒ KademliaRpc[C],
    conf: KademliaConf,
    checkNode: Node[C] ⇒ F[Boolean]
  ): Kademlia[F, C] = {
    implicit val bucketOps: Bucket.WriteOps[IO, C] = MVarBucketOps(conf.maxBucketSize)
    implicit val siblingOps: Siblings.WriteOps[IO, C] = KademliaMVar.siblingsOps(nodeId, conf.maxSiblingsSize)

    Kademlia[IO, IO.Par, C](
      nodeId,
      conf.parallelism,
      conf.pingExpiresIn,
      checkNode,
      contact.map(c ⇒ Node(nodeId, Instant.now(), c)),
      rpcForContact
    )
  }

  /**
   * Builder for client-side implementation of KademliaMVar
   *
   * @param rpc       Getter for RPC calling of another nodes
   * @param conf      Kademlia conf
   * @param checkNode Node could be saved to RoutingTable only if checker returns F[ true ]
   * @tparam C Contact info
   */
  def client[C](
    rpc: C ⇒ KademliaRpc[C],
    conf: KademliaConf,
    checkNode: Node[C] ⇒ IO[Boolean]
  ): Kademlia[IO, C] =
    apply[C](
      Monoid.empty[Key],
      IO.raiseError(new IllegalStateException("Client may not have a Contact")),
      rpc,
      conf,
      checkNode
    )

  /**
   * Performs atomic update on a MVar, blocking asynchronously if another update is in progress.
   *
   * @param mvar       State variable
   * @param mod        Modifier
   * @param updateRead Callback to update read model
   * @tparam S State
   * @tparam T Return value
   */
  private def runOnMVar[S, T](mvar: MVar[IO, S], mod: StateT[IO, S, T], updateRead: S ⇒ Unit): IO[T] =
    mvar.take.bracketCase { init ⇒
      mod.run(init).flatMap {
        case (updated, value) ⇒
          // Update read and write states
          updateRead(updated)
          mvar.put(updated).map(_ ⇒ value)
      }
    } {
      // In case of error, revert initial value
      case (_, ExitCase.Completed) ⇒ IO.unit
      case (init, _) ⇒ mvar.put(init)
    }

  /**
   * Builds asynchronous sibling ops with $maxSiblings nodes max.
   *
   * @param nodeId      Siblings are sorted by distance to this nodeId
   * @param maxSiblings Max number of closest siblings to store
   * @tparam C Node contacts type
   */
  private def siblingsOps[C](nodeId: Key, maxSiblings: Int): Siblings.WriteOps[IO, C] =
    new Siblings.WriteOps[IO, C] {
      private val readState = AtomicAny(Siblings[C](nodeId, maxSiblings))
      private val writeState = MVar.of(Siblings[C](nodeId, maxSiblings)).memoize

      override protected def run[T](mod: StateT[IO, Siblings[C], T]): IO[T] =
        for {
          ws ← writeState
          res ← runOnMVar(
            ws,
            mod,
            readState.set
          )
        } yield res

      override def read: Siblings[C] =
        readState.get
    }
}
