/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.events

import org.apache.kyuubi.Utils
import org.apache.kyuubi.operation.{KyuubiOperation, OperationHandle}
import org.apache.kyuubi.session.KyuubiSession

/**
 * A [[KyuubiOperationEvent]] used to tracker the lifecycle of an operation at server side.
 * <ul>
 *   <li>Operation Basis</li>
 *   <li>Operation Live Status</li>
 *   <li>Parent Session Id</li>
 * </ul>
 *
 * @param statementId the unique identifier of a single operation
 * @param remoteId the unique identifier of a single operation at engine side
 * @param statement the sql that you execute
 * @param shouldRunAsync the flag indicating whether the query runs synchronously or not
 * @param state the current operation state
 * @param eventTime the time when the event created & logged
 * @param createTime the time for changing to the current operation state
 * @param startTime the time the query start to time of this operation
 * @param completeTime time time the query ends
 * @param exception: caught exception if have
 * @param sessionId the identifier of the parent session
 * @param sessionUser the authenticated client user
 * @param sessionType the type of the parent session
 * @param kyuubiInstance the parent session connection url
 */
case class KyuubiOperationEvent private (
    statementId: String,
    remoteId: String,
    statement: String,
    shouldRunAsync: Boolean,
    state: String,
    eventTime: Long,
    createTime: Long,
    startTime: Long,
    completeTime: Long,
    exception: Option[Throwable],
    sessionId: String,
    sessionUser: String,
    sessionType: String,
    kyuubiInstance: String) extends KyuubiEvent {

  // operation events are partitioned by the date when the corresponding operations are
  // created.
  override def partitions: Seq[(String, String)] =
    ("day", Utils.getDateFromTimestamp(createTime)) :: Nil
}

object KyuubiOperationEvent {

  /**
   * Shorthand for instantiating a operation event with a [[KyuubiOperation]] instance
   */
  def apply(operation: KyuubiOperation): KyuubiOperationEvent = {
    val session = operation.getSession.asInstanceOf[KyuubiSession]
    val status = operation.getStatus
    new KyuubiOperationEvent(
      operation.statementId,
      Option(operation.remoteOpHandle()).map(OperationHandle(_).identifier.toString).orNull,
      operation.statement,
      operation.shouldRunAsync,
      status.state.name(),
      status.lastModified,
      status.create,
      status.start,
      status.completed,
      status.exception,
      session.handle.identifier.toString,
      session.user,
      session.sessionType.toString,
      session.connectionUrl)
  }
}
