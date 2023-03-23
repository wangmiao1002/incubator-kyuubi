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

package org.apache.kyuubi.engine

import java.util

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.engine.ApplicationState.{ApplicationState, FAILED, FINISHED, PENDING, RUNNING, UNKNOWN}
import org.apache.kyuubi.engine.KubernetesApplicationOperation.{toApplicationState, SPARK_APP_ID_LABEL}
import org.apache.kyuubi.util.KubernetesUtils

class KubernetesApplicationOperation extends ApplicationOperation with Logging {

  @volatile
  private var kubernetesClient: KubernetesClient = _

  private var submitTimeout: Long = _

  override def initialize(conf: KyuubiConf): Unit = {
    info("Start initializing Kubernetes Client.")
    kubernetesClient = KubernetesUtils.buildKubernetesClient(conf) match {
      case Some(client) =>
        info(s"Initialized Kubernetes Client connect to: ${client.getMasterUrl}")
        submitTimeout = conf.get(KyuubiConf.ENGINE_SUBMIT_TIMEOUT)
        client
      case None =>
        warn("Fail to init Kubernetes Client for Kubernetes Application Operation")
        null
    }
  }

  override def isSupported(clusterManager: Option[String]): Boolean = {
    // TODO add deploy mode to check whether is supported
    kubernetesClient != null && clusterManager.nonEmpty &&
    clusterManager.get.toLowerCase.startsWith("k8s")
  }

  override def killApplicationByTag(tag: String): KillResponse = {
    if (kubernetesClient != null) {
      debug(s"Deleting application info from Kubernetes cluster by $tag tag")
      try {
        // Need driver only
        val podList = findDriverPodByTag(tag)
        if (podList.size() != 0) {
          val targetPod = podList.get(0)
          toApplicationState(targetPod.getStatus.getPhase) match {
            case FAILED | UNKNOWN =>
              (
                false,
                s"Target Pod ${targetPod.getMetadata.getName} is in FAILED or UNKNOWN status")
            case _ =>
              (
                !kubernetesClient.pods.withName(targetPod.getMetadata.getName).delete().isEmpty,
                s"Operation of deleted appId: ${podList.get(0).getMetadata.getName} is completed")
          }
        } else {
          (
            false,
            s"Target Pod(tag: $tag) is not found, due to pod have been deleted or not created")
        }
      } catch {
        case e: Exception =>
          (false, s"Failed to terminate application with $tag, due to ${e.getMessage}")
      }
    } else {
      throw new IllegalStateException("Methods initialize and isSupported must be called ahead")
    }
  }

  override def getApplicationInfoByTag(tag: String, submitTime: Option[Long]): ApplicationInfo = {
    if (kubernetesClient == null) {
      throw new IllegalStateException("Methods initialize and isSupported must be called ahead")
    }
    debug(s"Getting application info from Kubernetes cluster by $tag tag")
    try {
      val podList = findDriverPodByTag(tag)
      if (podList.size() != 0) {
        val pod = podList.get(0)
        val info = ApplicationInfo(
          // spark pods always tag label `spark-app-selector:<spark-app-id>`
          id = pod.getMetadata.getLabels.get(SPARK_APP_ID_LABEL),
          name = pod.getMetadata.getName,
          state = KubernetesApplicationOperation.toApplicationState(pod.getStatus.getPhase),
          error = Option(pod.getStatus.getReason))
        debug(s"Successfully got application info by $tag: $info")
        return info
      }
      // Kyuubi should wait second if pod is not be created
      submitTime match {
        case Some(time) =>
          val elapsedTime = System.currentTimeMillis() - time
          if (elapsedTime > submitTimeout) {
            error(s"Can't find target driver pod by tag: $tag, " +
              s"elapsed time: ${elapsedTime}ms exceeds ${submitTimeout}ms.")
            ApplicationInfo(id = null, name = null, ApplicationState.NOT_FOUND)
          } else {
            warn("Wait for driver pod to be created, " +
              s"elapsed time: ${elapsedTime}ms, return UNKNOWN status")
            ApplicationInfo(id = null, name = null, ApplicationState.UNKNOWN)
          }
        case None =>
          ApplicationInfo(id = null, name = null, ApplicationState.NOT_FOUND)
      }
    } catch {
      case e: Exception =>
        error(s"Failed to get application with $tag, due to ${e.getMessage}")
        ApplicationInfo(id = null, name = null, ApplicationState.NOT_FOUND)
    }
  }

  private def findDriverPodByTag(tag: String): util.List[Pod] = {
    val podList = kubernetesClient.pods()
      .withLabel(KubernetesApplicationOperation.LABEL_KYUUBI_UNIQUE_KEY, tag).list().getItems
    val size = podList.size()
    if (size != 1) {
      warn(s"Get Tag: ${tag} Driver Pod In Kubernetes size: ${size}, we expect 1")
    }
    podList
  }

  override def stop(): Unit = {
    if (kubernetesClient != null) {
      try {
        kubernetesClient.close()
      } catch {
        case e: Exception => error(e.getMessage)
      }
    }
  }
}

object KubernetesApplicationOperation extends Logging {
  val LABEL_KYUUBI_UNIQUE_KEY = "kyuubi-unique-tag"
  val SPARK_APP_ID_LABEL = "spark-app-selector"
  val KUBERNETES_SERVICE_HOST = "KUBERNETES_SERVICE_HOST"
  val KUBERNETES_SERVICE_PORT = "KUBERNETES_SERVICE_PORT"

  def toApplicationState(state: String): ApplicationState = state match {
    // https://github.com/kubernetes/kubernetes/blob/master/pkg/apis/core/types.go#L2396
    // https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/
    case "Pending" => PENDING
    case "Running" => RUNNING
    case "Succeeded" => FINISHED
    case "Failed" | "Error" => FAILED
    case "Unknown" => ApplicationState.UNKNOWN
    case _ =>
      warn(s"The kubernetes driver pod state: $state is not supported, " +
        "mark the application state as UNKNOWN.")
      ApplicationState.UNKNOWN
  }
}
