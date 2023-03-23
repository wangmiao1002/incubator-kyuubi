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

import java.util.{Base64, UUID}
import javax.ws.rs.client.Entity
import javax.ws.rs.core.{GenericType, MediaType}

import scala.collection.JavaConverters._

import org.apache.hive.service.rpc.thrift.TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V2
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

import org.apache.kyuubi.{KYUUBI_VERSION, KyuubiFunSuite, RestFrontendTestHelper, Utils}
import org.apache.kyuubi.client.api.v1.dto.{Engine, OperationData, SessionData, SessionHandle, SessionOpenRequest}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_SESSION_CONNECTION_URL_KEY
import org.apache.kyuubi.engine.{ApplicationState, EngineRef, KyuubiApplicationManager}
import org.apache.kyuubi.engine.EngineType.SPARK_SQL
import org.apache.kyuubi.engine.ShareLevel.{CONNECTION, USER}
import org.apache.kyuubi.ha.HighAvailabilityConf
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.ha.client.DiscoveryPaths
import org.apache.kyuubi.plugin.PluginLoader
import org.apache.kyuubi.server.http.authentication.AuthenticationHandler.AUTHORIZATION_HEADER

class AdminResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  private val engineMgr = new KyuubiApplicationManager()

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(KyuubiConf.SERVER_ADMINISTRATORS, Seq("admin001"))

  override def beforeAll(): Unit = {
    super.beforeAll()
    engineMgr.initialize(KyuubiConf())
    engineMgr.start()
  }

  override def afterAll(): Unit = {
    engineMgr.stop()
    super.afterAll()
  }

  test("refresh Hadoop configuration of the kyuubi server") {
    var response = webTarget.path("api/v1/admin/refresh/hadoop_conf")
      .request()
      .post(null)
    assert(405 == response.getStatus)

    val adminUser = Utils.currentUser
    val encodeAuthorization = new String(
      Base64.getEncoder.encode(
        s"$adminUser:".getBytes()),
      "UTF-8")
    response = webTarget.path("api/v1/admin/refresh/hadoop_conf")
      .request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .post(null)
    assert(200 == response.getStatus)

    val admin001AuthHeader = new String(
      Base64.getEncoder.encode("admin001".getBytes()),
      "UTF-8")
    response = webTarget.path("api/v1/admin/refresh/hadoop_conf")
      .request()
      .header(AUTHORIZATION_HEADER, s"BASIC $admin001AuthHeader")
      .post(null)
    assert(200 == response.getStatus)

    val admin002AuthHeader = new String(
      Base64.getEncoder.encode("admin002".getBytes()),
      "UTF-8")
    response = webTarget.path("api/v1/admin/refresh/hadoop_conf")
      .request()
      .header(AUTHORIZATION_HEADER, s"BASIC $admin002AuthHeader")
      .post(null)
    assert(405 == response.getStatus)
  }

  test("refresh user defaults config of the kyuubi server") {
    var response = webTarget.path("api/v1/admin/refresh/user_defaults_conf")
      .request()
      .post(null)
    assert(405 == response.getStatus)

    val adminUser = Utils.currentUser
    val encodeAuthorization = new String(
      Base64.getEncoder.encode(
        s"$adminUser:".getBytes()),
      "UTF-8")
    response = webTarget.path("api/v1/admin/refresh/user_defaults_conf")
      .request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .post(null)
    assert(200 == response.getStatus)
  }

  test("refresh unlimited users of the kyuubi server") {
    var response = webTarget.path("api/v1/admin/refresh/unlimited_users")
      .request()
      .post(null)
    assert(405 == response.getStatus)

    val adminUser = Utils.currentUser
    val encodeAuthorization = new String(
      Base64.getEncoder.encode(
        s"$adminUser:".getBytes()),
      "UTF-8")
    response = webTarget.path("api/v1/admin/refresh/unlimited_users")
      .request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .post(null)
    assert(200 == response.getStatus)
  }

  test("list/close sessions") {
    val requestObj = new SessionOpenRequest(Map("testConfig" -> "testValue").asJava)

    var response = webTarget.path("api/v1/sessions")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(requestObj, MediaType.APPLICATION_JSON_TYPE))

    val adminUser = Utils.currentUser
    val encodeAuthorization = new String(
      Base64.getEncoder.encode(
        s"$adminUser:".getBytes()),
      "UTF-8")

    // get session list
    var response2 = webTarget.path("api/v1/admin/sessions").request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .get()
    assert(200 == response2.getStatus)
    val sessions1 = response2.readEntity(new GenericType[Seq[SessionData]]() {})
    assert(sessions1.nonEmpty)
    assert(sessions1.head.getConf.get(KYUUBI_SESSION_CONNECTION_URL_KEY) === fe.connectionUrl)

    // close an opened session
    val sessionHandle = response.readEntity(classOf[SessionHandle]).getIdentifier
    response = webTarget.path(s"api/v1/admin/sessions/$sessionHandle").request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .delete()
    assert(200 == response.getStatus)

    // get session list again
    response2 = webTarget.path("api/v1/admin/sessions").request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .get()
    assert(200 == response2.getStatus)
    val sessions2 = response2.readEntity(classOf[Seq[SessionData]])
    assert(sessions2.isEmpty)
  }

  test("list sessions/operations with filter") {
    fe.be.openSession(
      HIVE_CLI_SERVICE_PROTOCOL_V2,
      "admin",
      "123456",
      "localhost",
      Map("testConfig" -> "testValue"))

    fe.be.openSession(
      HIVE_CLI_SERVICE_PROTOCOL_V2,
      "admin",
      "123456",
      "localhost",
      Map("testConfig" -> "testValue"))

    fe.be.openSession(
      HIVE_CLI_SERVICE_PROTOCOL_V2,
      "test_user_1",
      "xxxxxx",
      "localhost",
      Map("testConfig" -> "testValue"))

    fe.be.openSession(
      HIVE_CLI_SERVICE_PROTOCOL_V2,
      "test_user_2",
      "xxxxxx",
      "localhost",
      Map("testConfig" -> "testValue"))

    val adminUser = Utils.currentUser
    val encodeAuthorization = new String(
      Base64.getEncoder.encode(
        s"$adminUser:".getBytes()),
      "UTF-8")

    // list sessions
    var response = webTarget.path("api/v1/admin/sessions")
      .queryParam("users", "admin")
      .request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .get()
    var sessions = response.readEntity(classOf[Seq[SessionData]])
    assert(200 == response.getStatus)
    assert(sessions.size == 2)

    response = webTarget.path("api/v1/admin/sessions")
      .queryParam("users", "test_user_1,test_user_2")
      .request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .get()
    sessions = response.readEntity(classOf[Seq[SessionData]])
    assert(200 == response.getStatus)
    assert(sessions.size == 2)

    // list operations
    response = webTarget.path("api/v1/admin/operations")
      .queryParam("users", "test_user_1,test_user_2")
      .request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .get()
    val operations = response.readEntity(classOf[Seq[OperationData]])
    assert(operations.size == 2)
  }

  test("list/close operations") {
    val sessionHandle = fe.be.openSession(
      HIVE_CLI_SERVICE_PROTOCOL_V2,
      "admin",
      "123456",
      "localhost",
      Map("testConfig" -> "testValue"))
    val operation = fe.be.getCatalogs(sessionHandle)

    val adminUser = Utils.currentUser
    val encodeAuthorization = new String(
      Base64.getEncoder.encode(
        s"$adminUser:".getBytes()),
      "UTF-8")

    // list operations
    var response = webTarget.path("api/v1/admin/operations").request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .get()
    assert(200 == response.getStatus)
    var operations = response.readEntity(new GenericType[Seq[OperationData]]() {})
    assert(operations.nonEmpty)
    assert(operations.map(op => op.getIdentifier).contains(operation.identifier.toString))

    // close operation
    response = webTarget.path(s"api/v1/admin/operations/${operation.identifier}").request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .delete()
    assert(200 == response.getStatus)

    // list again
    response = webTarget.path("api/v1/admin/operations").request()
      .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
      .get()
    operations = response.readEntity(new GenericType[Seq[OperationData]]() {})
    assert(!operations.map(op => op.getIdentifier).contains(operation.identifier.toString))
  }

  test("delete engine - user share level") {
    val id = UUID.randomUUID().toString
    conf.set(KyuubiConf.ENGINE_SHARE_LEVEL, USER.toString)
    conf.set(KyuubiConf.ENGINE_TYPE, SPARK_SQL.toString)
    conf.set(KyuubiConf.FRONTEND_THRIFT_BINARY_BIND_PORT, 0)
    conf.set(HighAvailabilityConf.HA_NAMESPACE, "kyuubi_test")
    conf.set(KyuubiConf.ENGINE_IDLE_TIMEOUT, 180000L)
    conf.set(KyuubiConf.GROUP_PROVIDER, "hadoop")

    val engine =
      new EngineRef(conf.clone, Utils.currentUser, PluginLoader.loadGroupProvider(conf), id, null)

    val engineSpace = DiscoveryPaths.makePath(
      s"kyuubi_test_${KYUUBI_VERSION}_USER_SPARK_SQL",
      Utils.currentUser,
      "default")

    withDiscoveryClient(conf) { client =>
      engine.getOrCreate(client)

      assert(client.pathExists(engineSpace))
      assert(client.getChildren(engineSpace).size == 1)

      val adminUser = Utils.currentUser
      val encodeAuthorization = new String(
        Base64.getEncoder.encode(
          s"$adminUser:".getBytes()),
        "UTF-8")
      val response = webTarget.path("api/v1/admin/engine")
        .queryParam("sharelevel", "USER")
        .queryParam("type", "spark_sql")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
        .delete()

      assert(200 == response.getStatus)
      assert(client.pathExists(engineSpace))
      eventually(timeout(5.seconds), interval(100.milliseconds)) {
        assert(client.getChildren(engineSpace).size == 0, s"refId same with $id?")
      }

      // kill the engine application
      engineMgr.killApplication(None, id)
      eventually(timeout(30.seconds), interval(100.milliseconds)) {
        assert(engineMgr.getApplicationInfo(None, id).exists(_.state == ApplicationState.NOT_FOUND))
      }
    }
  }

  test("delete engine - connection share level") {
    conf.set(KyuubiConf.ENGINE_SHARE_LEVEL, CONNECTION.toString)
    conf.set(KyuubiConf.ENGINE_TYPE, SPARK_SQL.toString)
    conf.set(KyuubiConf.FRONTEND_THRIFT_BINARY_BIND_PORT, 0)
    conf.set(HighAvailabilityConf.HA_NAMESPACE, "kyuubi_test")
    conf.set(KyuubiConf.ENGINE_IDLE_TIMEOUT, 180000L)
    conf.set(KyuubiConf.GROUP_PROVIDER, "hadoop")

    val id = UUID.randomUUID().toString
    val engine =
      new EngineRef(conf.clone, Utils.currentUser, PluginLoader.loadGroupProvider(conf), id, null)
    val engineSpace = DiscoveryPaths.makePath(
      s"kyuubi_test_${KYUUBI_VERSION}_CONNECTION_SPARK_SQL",
      Utils.currentUser,
      id)

    withDiscoveryClient(conf) { client =>
      engine.getOrCreate(client)

      assert(client.pathExists(engineSpace))
      assert(client.getChildren(engineSpace).size == 1)

      val adminUser = Utils.currentUser
      val encodeAuthorization = new String(
        Base64.getEncoder.encode(
          s"$adminUser:".getBytes()),
        "UTF-8")
      val response = webTarget.path("api/v1/admin/engine")
        .queryParam("sharelevel", "connection")
        .queryParam("type", "spark_sql")
        .queryParam("subdomain", id)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
        .delete()

      assert(200 == response.getStatus)
    }
  }

  test("list engine - user share level") {
    val id = UUID.randomUUID().toString
    conf.set(KyuubiConf.ENGINE_SHARE_LEVEL, USER.toString)
    conf.set(KyuubiConf.ENGINE_TYPE, SPARK_SQL.toString)
    conf.set(KyuubiConf.FRONTEND_THRIFT_BINARY_BIND_PORT, 0)
    conf.set(HighAvailabilityConf.HA_NAMESPACE, "kyuubi_test")
    conf.set(KyuubiConf.ENGINE_IDLE_TIMEOUT, 180000L)
    conf.set(KyuubiConf.GROUP_PROVIDER, "hadoop")

    val engine =
      new EngineRef(conf.clone, Utils.currentUser, PluginLoader.loadGroupProvider(conf), id, null)

    val engineSpace = DiscoveryPaths.makePath(
      s"kyuubi_test_${KYUUBI_VERSION}_USER_SPARK_SQL",
      Utils.currentUser,
      "")

    withDiscoveryClient(conf) { client =>
      engine.getOrCreate(client)

      assert(client.pathExists(engineSpace))
      assert(client.getChildren(engineSpace).size == 1)

      val adminUser = Utils.currentUser
      val encodeAuthorization = new String(
        Base64.getEncoder.encode(
          s"$adminUser:".getBytes()),
        "UTF-8")
      val response = webTarget.path("api/v1/admin/engine")
        .queryParam("type", "spark_sql")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
        .get

      assert(200 == response.getStatus)
      val engines = response.readEntity(new GenericType[Seq[Engine]]() {})
      assert(engines.size == 1)
      assert(engines(0).getEngineType == "SPARK_SQL")
      assert(engines(0).getSharelevel == "USER")
      assert(engines(0).getSubdomain == "default")

      // kill the engine application
      engineMgr.killApplication(None, id)
      eventually(timeout(30.seconds), interval(100.milliseconds)) {
        assert(engineMgr.getApplicationInfo(None, id).exists(_.state == ApplicationState.NOT_FOUND))
      }
    }
  }

  test("list engine - connection share level") {
    conf.set(KyuubiConf.ENGINE_SHARE_LEVEL, CONNECTION.toString)
    conf.set(KyuubiConf.ENGINE_TYPE, SPARK_SQL.toString)
    conf.set(KyuubiConf.FRONTEND_THRIFT_BINARY_BIND_PORT, 0)
    conf.set(HighAvailabilityConf.HA_NAMESPACE, "kyuubi_test")
    conf.set(KyuubiConf.ENGINE_IDLE_TIMEOUT, 180000L)
    conf.set(KyuubiConf.GROUP_PROVIDER, "hadoop")

    val engineSpace = DiscoveryPaths.makePath(
      s"kyuubi_test_${KYUUBI_VERSION}_CONNECTION_SPARK_SQL",
      Utils.currentUser,
      "")

    val id1 = UUID.randomUUID().toString
    val engine1 =
      new EngineRef(conf.clone, Utils.currentUser, PluginLoader.loadGroupProvider(conf), id1, null)
    val engineSpace1 = DiscoveryPaths.makePath(
      s"kyuubi_test_${KYUUBI_VERSION}_CONNECTION_SPARK_SQL",
      Utils.currentUser,
      id1)

    val id2 = UUID.randomUUID().toString
    val engine2 =
      new EngineRef(conf.clone, Utils.currentUser, PluginLoader.loadGroupProvider(conf), id2, null)
    val engineSpace2 = DiscoveryPaths.makePath(
      s"kyuubi_test_${KYUUBI_VERSION}_CONNECTION_SPARK_SQL",
      Utils.currentUser,
      id2)

    withDiscoveryClient(conf) { client =>
      engine1.getOrCreate(client)
      engine2.getOrCreate(client)

      assert(client.pathExists(engineSpace))
      assert(client.getChildren(engineSpace).size == 2)
      assert(client.pathExists(engineSpace1))
      assert(client.pathExists(engineSpace2))

      val adminUser = Utils.currentUser
      val encodeAuthorization = new String(
        Base64.getEncoder.encode(
          s"$adminUser:".getBytes()),
        "UTF-8")
      val response = webTarget.path("api/v1/admin/engine")
        .queryParam("type", "spark_sql")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
        .get
      assert(200 == response.getStatus)
      val result = response.readEntity(new GenericType[Seq[Engine]]() {})
      assert(result.size == 2)

      val response1 = webTarget.path("api/v1/admin/engine")
        .queryParam("type", "spark_sql")
        .queryParam("subdomain", id1)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .header(AUTHORIZATION_HEADER, s"BASIC $encodeAuthorization")
        .get
      assert(200 == response1.getStatus)
      val result1 = response1.readEntity(new GenericType[Seq[Engine]]() {})
      assert(result1.size == 1)

      // kill the engine application
      engineMgr.killApplication(None, id1)
      engineMgr.killApplication(None, id2)
      eventually(timeout(30.seconds), interval(100.milliseconds)) {
        assert(engineMgr.getApplicationInfo(None, id1)
          .exists(_.state == ApplicationState.NOT_FOUND))
        assert(engineMgr.getApplicationInfo(None, id2)
          .exists(_.state == ApplicationState.NOT_FOUND))
      }
    }
  }

}
