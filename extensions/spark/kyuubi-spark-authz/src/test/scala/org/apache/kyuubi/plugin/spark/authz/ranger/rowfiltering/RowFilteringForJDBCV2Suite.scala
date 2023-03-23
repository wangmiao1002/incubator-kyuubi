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

package org.apache.kyuubi.plugin.spark.authz.ranger.rowfiltering

import java.sql.DriverManager

import scala.util.Try

import org.apache.spark.SparkConf
import org.scalatest.Outcome

class RowFilteringForJDBCV2Suite extends RowFilteringTestBase {
  override protected val extraSparkConf: SparkConf = {
    val conf = new SparkConf()
    if (isSparkV31OrGreater) {
      conf
        .set("spark.sql.defaultCatalog", "testcat")
        .set(
          "spark.sql.catalog.testcat",
          "org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog")
        .set(s"spark.sql.catalog.testcat.url", "jdbc:derby:memory:testcat;create=true")
        .set(
          s"spark.sql.catalog.testcat.driver",
          "org.apache.derby.jdbc.AutoloadedDriver")
    }
    conf
  }

  override protected val catalogImpl: String = "in-memory"

  override protected def format: String = ""

  override def beforeAll(): Unit = {
    if (isSparkV31OrGreater) super.beforeAll()
  }

  override def afterAll(): Unit = {
    if (isSparkV31OrGreater) {
      super.afterAll()
      // cleanup db
      Try {
        DriverManager.getConnection(s"jdbc:derby:memory:testcat;shutdown=true")
      }
    }
  }

  override def withFixture(test: NoArgTest): Outcome = {
    assume(isSparkV31OrGreater)
    test()
  }
}
