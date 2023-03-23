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

package org.apache.kyuubi.server.api.v1

import java.util.Collections
import javax.ws.rs._
import javax.ws.rs.core.{MediaType, Response}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import io.swagger.v3.oas.annotations.media.{ArraySchema, Content, Schema}
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.apache.commons.lang3.StringUtils
import org.apache.zookeeper.KeeperException.NoNodeException

import org.apache.kyuubi.{KYUUBI_VERSION, Logging, Utils}
import org.apache.kyuubi.client.api.v1.dto.{Engine, OperationData, SessionData}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.{DiscoveryPaths, ServiceNodeInfo}
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.operation.{KyuubiOperation, OperationHandle}
import org.apache.kyuubi.server.KyuubiServer
import org.apache.kyuubi.server.api.{ApiRequestContext, ApiUtils}
import org.apache.kyuubi.session.{KyuubiSession, SessionHandle}

@Tag(name = "Admin")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class AdminResource extends ApiRequestContext with Logging {
  private lazy val administrators = fe.getConf.get(KyuubiConf.SERVER_ADMINISTRATORS).toSet +
    Utils.currentUser

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "refresh the Kyuubi server hadoop conf, note that, " +
      "it only takes affect for frontend services now")
  @POST
  @Path("refresh/hadoop_conf")
  def refreshFrontendHadoopConf(): Response = {
    val userName = fe.getSessionUser(Map.empty[String, String])
    val ipAddress = fe.getIpAddress
    info(s"Receive refresh Kyuubi server hadoop conf request from $userName/$ipAddress")
    if (!isAdministrator(userName)) {
      throw new NotAllowedException(
        s"$userName is not allowed to refresh the Kyuubi server hadoop conf")
    }
    info(s"Reloading the Kyuubi server hadoop conf")
    KyuubiServer.reloadHadoopConf()
    Response.ok(s"Refresh the hadoop conf for ${fe.connectionUrl} successfully.").build()
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "refresh the user defaults configs")
  @POST
  @Path("refresh/user_defaults_conf")
  def refreshUserDefaultsConf(): Response = {
    val userName = fe.getSessionUser(Map.empty[String, String])
    val ipAddress = fe.getIpAddress
    info(s"Receive refresh user defaults conf request from $userName/$ipAddress")
    if (!isAdministrator(userName)) {
      throw new NotAllowedException(
        s"$userName is not allowed to refresh the user defaults conf")
    }
    info(s"Reloading user defaults conf")
    KyuubiServer.refreshUserDefaultsConf()
    Response.ok(s"Refresh the user defaults conf successfully.").build()
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "refresh the unlimited users")
  @POST
  @Path("refresh/unlimited_users")
  def refreshUnlimitedUser(): Response = {
    val userName = fe.getSessionUser(Map.empty[String, String])
    val ipAddress = fe.getIpAddress
    info(s"Receive refresh unlimited users request from $userName/$ipAddress")
    if (!isAdministrator(userName)) {
      throw new NotAllowedException(
        s"$userName is not allowed to refresh the unlimited users")
    }
    info(s"Reloading unlimited users")
    KyuubiServer.refreshUnlimitedUsers()
    Response.ok(s"Refresh the unlimited users successfully.").build()
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      array = new ArraySchema(schema = new Schema(implementation = classOf[SessionData])))),
    description = "get the list of all live sessions")
  @GET
  @Path("sessions")
  def sessions(@QueryParam("users") users: String): Seq[SessionData] = {
    val userName = fe.getSessionUser(Map.empty[String, String])
    val ipAddress = fe.getIpAddress
    info(s"Received listing all live sessions request from $userName/$ipAddress")
    if (!isAdministrator(userName)) {
      throw new NotAllowedException(
        s"$userName is not allowed to list all live sessions")
    }
    var sessions = fe.be.sessionManager.allSessions()
    if (StringUtils.isNotBlank(users)) {
      val usersSet = users.split(",").toSet
      sessions = sessions.filter(session => usersSet.contains(session.user))
    }
    sessions.map { case session =>
      ApiUtils.sessionData(session.asInstanceOf[KyuubiSession])
    }.toSeq
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "Close a session")
  @DELETE
  @Path("sessions/{sessionHandle}")
  def closeSession(@PathParam("sessionHandle") sessionHandleStr: String): Response = {
    val userName = fe.getSessionUser(Map.empty[String, String])
    val ipAddress = fe.getIpAddress
    info(s"Received closing a session request from $userName/$ipAddress")
    if (!isAdministrator(userName)) {
      throw new NotAllowedException(
        s"$userName is not allowed to close the session $sessionHandleStr")
    }
    fe.be.closeSession(SessionHandle.fromUUID(sessionHandleStr))
    Response.ok(s"Session $sessionHandleStr is closed successfully.").build()
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      array = new ArraySchema(schema = new Schema(implementation =
        classOf[OperationData])))),
    description =
      "get the list of all active operations")
  @GET
  @Path("operations")
  def listOperations(@QueryParam("users") users: String): Seq[OperationData] = {
    val userName = fe.getSessionUser(Map.empty[String, String])
    val ipAddress = fe.getIpAddress
    info(s"Received listing all of the active operations request from $userName/$ipAddress")
    if (!isAdministrator(userName)) {
      throw new NotAllowedException(
        s"$userName is not allowed to list all the operations")
    }
    var operations = fe.be.sessionManager.operationManager.allOperations()
    if (StringUtils.isNotBlank(users)) {
      val usersSet = users.split(",").toSet
      operations = operations.filter(operation => usersSet.contains(operation.getSession.user))
    }
    operations
      .map(operation => ApiUtils.operationData(operation.asInstanceOf[KyuubiOperation])).toSeq
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "close an operation")
  @DELETE
  @Path("operations/{operationHandle}")
  def closeOperation(@PathParam("operationHandle") operationHandleStr: String): Response = {
    val userName = fe.getSessionUser(Map.empty[String, String])
    val ipAddress = fe.getIpAddress
    info(s"Received close an operation request from $userName/$ipAddress")
    if (!isAdministrator(userName)) {
      throw new NotAllowedException(
        s"$userName is not allowed to close the operation $operationHandleStr")
    }
    val operationHandle = OperationHandle(operationHandleStr)
    fe.be.closeOperation(operationHandle)
    Response.ok(s"Operation $operationHandleStr is closed successfully.").build()
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "delete kyuubi engine")
  @DELETE
  @Path("engine")
  def deleteEngine(
      @QueryParam("type") engineType: String,
      @QueryParam("sharelevel") shareLevel: String,
      @QueryParam("subdomain") subdomain: String,
      @QueryParam("hive.server2.proxy.user") hs2ProxyUser: String): Response = {
    val userName = if (isAdministrator(fe.getRealUser())) {
      Option(hs2ProxyUser).getOrElse(fe.getRealUser())
    } else {
      fe.getSessionUser(hs2ProxyUser)
    }
    val engine = getEngine(userName, engineType, shareLevel, subdomain, "default")
    val engineSpace = getEngineSpace(engine)

    withDiscoveryClient(fe.getConf) { discoveryClient =>
      val engineNodes = discoveryClient.getChildren(engineSpace)
      engineNodes.foreach { node =>
        val nodePath = s"$engineSpace/$node"
        info(s"Deleting engine node:$nodePath")
        try {
          discoveryClient.delete(nodePath)
        } catch {
          case e: Exception =>
            error(s"Failed to delete engine node:$nodePath", e)
            throw new NotFoundException(s"Failed to delete engine node:$nodePath," +
              s"${e.getMessage}")
        }
      }
    }

    Response.ok(s"Engine $engineSpace is deleted successfully.").build()
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "list kyuubi engines")
  @GET
  @Path("engine")
  def listEngines(
      @QueryParam("type") engineType: String,
      @QueryParam("sharelevel") shareLevel: String,
      @QueryParam("subdomain") subdomain: String,
      @QueryParam("hive.server2.proxy.user") hs2ProxyUser: String): Seq[Engine] = {
    val userName = if (isAdministrator(fe.getRealUser())) {
      Option(hs2ProxyUser).getOrElse(fe.getRealUser())
    } else {
      fe.getSessionUser(hs2ProxyUser)
    }
    val engine = getEngine(userName, engineType, shareLevel, subdomain, "")
    val engineSpace = getEngineSpace(engine)

    var engineNodes = ListBuffer[ServiceNodeInfo]()
    Option(subdomain).filter(_.nonEmpty) match {
      case Some(_) =>
        withDiscoveryClient(fe.getConf) { discoveryClient =>
          info(s"Listing engine nodes for $engineSpace")
          engineNodes ++= discoveryClient.getServiceNodesInfo(engineSpace)
        }
      case None =>
        withDiscoveryClient(fe.getConf) { discoveryClient =>
          try {
            discoveryClient.getChildren(engineSpace).map { child =>
              info(s"Listing engine nodes for $engineSpace/$child")
              engineNodes ++= discoveryClient.getServiceNodesInfo(s"$engineSpace/$child")
            }
          } catch {
            case nne: NoNodeException =>
              error(
                s"No such engine for user: $userName, " +
                  s"engine type: $engineType, share level: $shareLevel, subdomain: $subdomain",
                nne)
              throw new NotFoundException(s"No such engine for user: $userName, " +
                s"engine type: $engineType, share level: $shareLevel, subdomain: $subdomain")
          }
        }
    }
    engineNodes.map(node =>
      new Engine(
        engine.getVersion,
        engine.getUser,
        engine.getEngineType,
        engine.getSharelevel,
        node.namespace.split("/").last,
        node.instance,
        node.namespace,
        node.attributes.asJava))
  }

  private def getEngine(
      userName: String,
      engineType: String,
      shareLevel: String,
      subdomain: String,
      subdomainDefault: String): Engine = {
    // use default value from kyuubi conf when param is not provided
    val clonedConf: KyuubiConf = fe.getConf.clone
    Option(engineType).foreach(clonedConf.set(ENGINE_TYPE, _))
    Option(subdomain).filter(_.nonEmpty)
      .foreach(_ => clonedConf.set(ENGINE_SHARE_LEVEL_SUBDOMAIN, Option(subdomain)))
    Option(shareLevel).filter(_.nonEmpty).foreach(clonedConf.set(ENGINE_SHARE_LEVEL, _))

    val normalizedEngineType = clonedConf.get(ENGINE_TYPE)
    val engineSubdomain = clonedConf.get(ENGINE_SHARE_LEVEL_SUBDOMAIN).getOrElse(subdomainDefault)
    val engineShareLevel = clonedConf.get(ENGINE_SHARE_LEVEL)

    new Engine(
      KYUUBI_VERSION,
      userName,
      normalizedEngineType,
      engineShareLevel,
      engineSubdomain,
      null,
      null,
      Collections.emptyMap())
  }

  private def getEngineSpace(engine: Engine): String = {
    val serverSpace = fe.getConf.get(HA_NAMESPACE)
    DiscoveryPaths.makePath(
      s"${serverSpace}_${engine.getVersion}_${engine.getSharelevel}_${engine.getEngineType}",
      engine.getUser,
      engine.getSubdomain)
  }

  private def isAdministrator(userName: String): Boolean = {
    administrators.contains(userName);
  }
}
