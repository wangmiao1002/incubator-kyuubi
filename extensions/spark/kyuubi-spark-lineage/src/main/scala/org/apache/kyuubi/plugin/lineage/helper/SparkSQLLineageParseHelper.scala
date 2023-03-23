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

package org.apache.kyuubi.plugin.lineage.helper

import scala.collection.immutable.ListMap
import scala.util.{Failure, Success, Try}

import org.apache.spark.internal.Logging
import org.apache.spark.kyuubi.lineage.{LineageConf, SparkContextHelper}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{NamedRelation, PersistedView, ViewType}
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable, HiveTableRelation}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeSet, Expression, NamedExpression, ScalarSubquery}
import org.apache.spark.sql.catalyst.expressions.aggregate.Count
import org.apache.spark.sql.catalyst.plans.{LeftAnti, LeftSemi}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.connector.catalog.{CatalogPlugin, Identifier, TableCatalog}
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.columnar.InMemoryRelation
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.execution.datasources.v2.{DataSourceV2Relation, DataSourceV2ScanRelation}

import org.apache.kyuubi.plugin.lineage.Lineage
import org.apache.kyuubi.plugin.lineage.helper.SparkListenerHelper.isSparkVersionAtMost

trait LineageParser {
  def sparkSession: SparkSession

  val SUBQUERY_COLUMN_IDENTIFIER = "__subquery__"
  val AGGREGATE_COUNT_COLUMN_IDENTIFIER = "__count__"
  val LOCAL_TABLE_IDENTIFIER = "__local__"

  type AttributeMap[A] = ListMap[Attribute, A]

  def parse(plan: LogicalPlan): Lineage = {
    val columnsLineage =
      extractColumnsLineage(plan, ListMap[Attribute, AttributeSet]()).toList.collect {
        case (k, attrs) =>
          k.name -> attrs.map(_.qualifiedName).toSet
      }
    val (inputTables, outputTables) = columnsLineage.foldLeft((List[String](), List[String]())) {
      case ((inputs, outputs), (out, in)) =>
        val x = (inputs ++ in.map(_.split('.').init.mkString("."))).filter(_.nonEmpty)
        val y = outputs ++ List(out.split('.').init.mkString(".")).filter(_.nonEmpty)
        (x, y)
    }
    Lineage(inputTables.distinct, outputTables.distinct, columnsLineage)
  }

  private def mergeColumnsLineage(
      left: AttributeMap[AttributeSet],
      right: AttributeMap[AttributeSet]): AttributeMap[AttributeSet] = {
    left ++ right.map {
      case (k, attrs) =>
        k -> (attrs ++ left.getOrElse(k, AttributeSet.empty))
    }
  }

  private def joinColumnsLineage(
      parent: AttributeMap[AttributeSet],
      child: AttributeMap[AttributeSet]): AttributeMap[AttributeSet] = {
    if (parent.isEmpty) child
    else {
      val childMap = child.map { case (k, attrs) => (k.exprId, attrs) }
      parent.map { case (k, attrs) =>
        k -> AttributeSet(attrs.flatMap(attr =>
          childMap.getOrElse(
            attr.exprId,
            if (attr.name.equalsIgnoreCase(AGGREGATE_COUNT_COLUMN_IDENTIFIER)) AttributeSet(attr)
            else AttributeSet.empty)))
      }
    }
  }

  private def getExpressionSubqueryPlans(expression: Expression): Seq[LogicalPlan] = {
    expression match {
      case s: ScalarSubquery => Seq(s.plan)
      case s => s.children.flatMap(getExpressionSubqueryPlans)
    }
  }

  private def findSparkPlanLogicalLink(sparkPlans: Seq[SparkPlan]): Option[LogicalPlan] = {
    sparkPlans.find(_.logicalLink.nonEmpty) match {
      case Some(sparkPlan) => sparkPlan.logicalLink
      case None => findSparkPlanLogicalLink(sparkPlans.flatMap(_.children))
    }
  }

  private def containsCountAll(expr: Expression): Boolean = {
    expr match {
      case e: Count if e.references.isEmpty => true
      case e =>
        e.children.exists(containsCountAll)
    }
  }

  private def getSelectColumnLineage(
      named: Seq[NamedExpression]): AttributeMap[AttributeSet] = {
    val exps = named.map {
      case exp: Alias =>
        val references =
          if (exp.references.nonEmpty) exp.references
          else {
            val attrRefs = getExpressionSubqueryPlans(exp.child)
              .map(extractColumnsLineage(_, ListMap[Attribute, AttributeSet]()))
              .foldLeft(ListMap[Attribute, AttributeSet]())(mergeColumnsLineage).values
              .foldLeft(AttributeSet.empty)(_ ++ _)
              .map(attr => attr.withQualifier(attr.qualifier :+ SUBQUERY_COLUMN_IDENTIFIER))
            AttributeSet(attrRefs)
          }
        (
          exp.toAttribute,
          if (!containsCountAll(exp.child)) references
          else references + exp.toAttribute.withName(AGGREGATE_COUNT_COLUMN_IDENTIFIER))
      case a: Attribute => a -> AttributeSet(a)
    }
    ListMap(exps: _*)
  }

  private def joinRelationColumnLineage(
      parent: AttributeMap[AttributeSet],
      relationAttrs: Seq[Attribute],
      qualifier: Seq[String]): AttributeMap[AttributeSet] = {
    val relationAttrSet = AttributeSet(relationAttrs)
    if (parent.nonEmpty) {
      parent.map { case (k, attrs) =>
        k -> AttributeSet(attrs.collect {
          case attr if relationAttrSet.contains(attr) =>
            attr.withQualifier(qualifier)
          case attr
              if attr.qualifier.nonEmpty && attr.qualifier.last.equalsIgnoreCase(
                SUBQUERY_COLUMN_IDENTIFIER) =>
            attr.withQualifier(attr.qualifier.init)
          case attr if attr.name.equalsIgnoreCase(AGGREGATE_COUNT_COLUMN_IDENTIFIER) =>
            attr.withQualifier(qualifier)
          case attr if isNameWithQualifier(attr, qualifier) =>
            val newName = attr.name.split('.').last.stripPrefix("`").stripSuffix("`")
            attr.withName(newName).withQualifier(qualifier)
        })
      }
    } else {
      ListMap(relationAttrs.map { attr =>
        (
          attr,
          AttributeSet(attr.withQualifier(qualifier)))
      }: _*)
    }
  }

  private def isNameWithQualifier(attr: Attribute, qualifier: Seq[String]): Boolean = {
    val nameTokens = attr.name.split('.')
    val namespace = nameTokens.init.mkString(".")
    nameTokens.length > 1 && namespace.endsWith(qualifier.mkString("."))
  }

  private def mergeRelationColumnLineage(
      parentColumnsLineage: AttributeMap[AttributeSet],
      relationOutput: Seq[Attribute],
      relationColumnLineage: AttributeMap[AttributeSet]): AttributeMap[AttributeSet] = {
    val mergedRelationColumnLineage = {
      relationOutput.foldLeft((ListMap[Attribute, AttributeSet](), relationColumnLineage)) {
        case ((acc, x), attr) =>
          (acc + (attr -> x.head._2), x.tail)
      }._1
    }
    joinColumnsLineage(parentColumnsLineage, mergedRelationColumnLineage)
  }

  private def extractColumnsLineage(
      plan: LogicalPlan,
      parentColumnsLineage: AttributeMap[AttributeSet]): AttributeMap[AttributeSet] = {

    plan match {
      // For command
      case p if p.nodeName == "CommandResult" =>
        val commandPlan = getPlanField[LogicalPlan]("commandLogicalPlan", plan)
        extractColumnsLineage(commandPlan, parentColumnsLineage)
      case p if p.nodeName == "AlterViewAsCommand" =>
        val query =
          if (isSparkVersionAtMost("3.1")) {
            sparkSession.sessionState.analyzer.execute(getQuery(plan))
          } else {
            getQuery(plan)
          }
        val view = getPlanField[TableIdentifier]("name", plan).unquotedString
        extractColumnsLineage(query, parentColumnsLineage).map { case (k, v) =>
          k.withName(s"$view.${k.name}") -> v
        }

      case p
          if p.nodeName == "CreateViewCommand"
            && getPlanField[ViewType]("viewType", plan) == PersistedView =>
        val view = getPlanField[TableIdentifier]("name", plan).unquotedString
        val outputCols =
          getPlanField[Seq[(String, Option[String])]]("userSpecifiedColumns", plan).map(_._1)
        val query =
          if (isSparkVersionAtMost("3.1")) {
            sparkSession.sessionState.analyzer.execute(getPlanField[LogicalPlan]("child", plan))
          } else {
            getPlanField[LogicalPlan]("plan", plan)
          }

        extractColumnsLineage(query, parentColumnsLineage).zipWithIndex.map {
          case ((k, v), i) if outputCols.nonEmpty => k.withName(s"$view.${outputCols(i)}") -> v
          case ((k, v), _) => k.withName(s"$view.${k.name}") -> v
        }

      case p if p.nodeName == "CreateDataSourceTableAsSelectCommand" =>
        val table = getPlanField[CatalogTable]("table", plan).qualifiedName
        extractColumnsLineage(getQuery(plan), parentColumnsLineage).map { case (k, v) =>
          k.withName(s"$table.${k.name}") -> v
        }

      case p
          if p.nodeName == "CreateHiveTableAsSelectCommand" ||
            p.nodeName == "OptimizedCreateHiveTableAsSelectCommand" =>
        val table = getPlanField[CatalogTable]("tableDesc", plan).qualifiedName
        extractColumnsLineage(getQuery(plan), parentColumnsLineage).map { case (k, v) =>
          k.withName(s"$table.${k.name}") -> v
        }

      case p
          if p.nodeName == "CreateTableAsSelect" ||
            p.nodeName == "ReplaceTableAsSelect" =>
        val (table, namespace, catalog) =
          if (isSparkVersionAtMost("3.2")) {
            (
              getPlanField[Identifier]("tableName", plan).name,
              getPlanField[Identifier]("tableName", plan).namespace.mkString("."),
              getPlanField[TableCatalog]("catalog", plan).name())
          } else {
            (
              getPlanMethod[Identifier]("tableName", plan).name(),
              getPlanMethod[Identifier]("tableName", plan).namespace().mkString("."),
              getCurrentPlanField[CatalogPlugin](
                getPlanMethod[LogicalPlan]("left", plan),
                "catalog").name())
          }
        extractColumnsLineage(getQuery(plan), parentColumnsLineage).map { case (k, v) =>
          k.withName(Seq(catalog, namespace, table, k.name).filter(_.nonEmpty).mkString(".")) -> v
        }

      case p if p.nodeName == "InsertIntoDataSourceCommand" =>
        val logicalRelation = getPlanField[LogicalRelation]("logicalRelation", plan)
        val table = logicalRelation.catalogTable.map(_.qualifiedName).getOrElse("")
        extractColumnsLineage(getQuery(plan), parentColumnsLineage).map {
          case (k, v) if table.nonEmpty =>
            k.withName(s"$table.${k.name}") -> v
        }

      case p if p.nodeName == "InsertIntoHadoopFsRelationCommand" =>
        val table =
          getPlanField[Option[CatalogTable]]("catalogTable", plan).map(_.qualifiedName).getOrElse(
            "")
        extractColumnsLineage(getQuery(plan), parentColumnsLineage).map {
          case (k, v) if table.nonEmpty =>
            k.withName(s"$table.${k.name}") -> v
        }

      case p
          if p.nodeName == "InsertIntoDataSourceDirCommand" ||
            p.nodeName == "InsertIntoHiveDirCommand" =>
        val dir =
          getPlanField[CatalogStorageFormat]("storage", plan).locationUri.map(_.toString).getOrElse(
            "")
        extractColumnsLineage(getQuery(plan), parentColumnsLineage).map {
          case (k, v) if dir.nonEmpty =>
            k.withName(s"`$dir`.${k.name}") -> v
        }

      case p if p.nodeName == "InsertIntoHiveTable" =>
        val table = getPlanField[CatalogTable]("table", plan).qualifiedName
        extractColumnsLineage(getQuery(plan), parentColumnsLineage).map { case (k, v) =>
          k.withName(s"$table.${k.name}") -> v
        }

      case p if p.nodeName == "SaveIntoDataSourceCommand" =>
        extractColumnsLineage(getQuery(plan), parentColumnsLineage)

      case p
          if p.nodeName == "AppendData"
            || p.nodeName == "OverwriteByExpression"
            || p.nodeName == "OverwritePartitionsDynamic" =>
        val table = getPlanField[NamedRelation]("table", plan).name
        extractColumnsLineage(getQuery(plan), parentColumnsLineage).map { case (k, v) =>
          k.withName(s"$table.${k.name}") -> v
        }

      case p if p.nodeName == "MergeIntoTable" =>
        val matchedActions = getPlanField[Seq[MergeAction]]("matchedActions", plan)
        val notMatchedActions = getPlanField[Seq[MergeAction]]("notMatchedActions", plan)
        val allAssignments = (matchedActions ++ notMatchedActions).collect {
          case UpdateAction(_, assignments) => assignments
          case InsertAction(_, assignments) => assignments
        }.flatten
        val nextColumnsLlineage = ListMap(allAssignments.map { assignment =>
          (
            assignment.key.asInstanceOf[Attribute],
            assignment.value.references)
        }: _*)
        val targetTable = getPlanField[LogicalPlan]("targetTable", plan)
        val sourceTable = getPlanField[LogicalPlan]("sourceTable", plan)
        val targetColumnsLineage = extractColumnsLineage(
          targetTable,
          nextColumnsLlineage.map { case (k, _) => (k, AttributeSet(k)) })
        val sourceColumnsLineage = extractColumnsLineage(sourceTable, nextColumnsLlineage)
        val targetColumnsWithTargetTable = targetColumnsLineage.values.flatten.map { column =>
          column.withName(s"${column.qualifiedName}")
        }
        ListMap(targetColumnsWithTargetTable.zip(sourceColumnsLineage.values).toSeq: _*)

      case p if p.nodeName == "WithCTE" =>
        val optimized = sparkSession.sessionState.optimizer.execute(p)
        extractColumnsLineage(optimized, parentColumnsLineage)

      // For query
      case p: Project =>
        val nextColumnsLineage =
          joinColumnsLineage(parentColumnsLineage, getSelectColumnLineage(p.projectList))
        p.children.map(extractColumnsLineage(_, nextColumnsLineage)).reduce(mergeColumnsLineage)

      case p: Aggregate =>
        val nextColumnsLineage =
          joinColumnsLineage(parentColumnsLineage, getSelectColumnLineage(p.aggregateExpressions))
        p.children.map(extractColumnsLineage(_, nextColumnsLineage)).reduce(mergeColumnsLineage)

      case p: Expand =>
        val references =
          p.projections.transpose.map(_.flatMap(x => x.references)).map(AttributeSet(_))

        val childColumnsLineage = ListMap(p.output.zip(references): _*)
        val nextColumnsLineage =
          joinColumnsLineage(parentColumnsLineage, childColumnsLineage)
        p.children.map(extractColumnsLineage(_, nextColumnsLineage)).reduce(mergeColumnsLineage)

      case p: Window =>
        val windowColumnsLineage =
          ListMap(p.windowExpressions.map(exp => (exp.toAttribute, exp.references)): _*)

        val nextColumnsLineage = if (parentColumnsLineage.isEmpty) {
          ListMap(p.child.output.map(attr => (attr, attr.references)): _*) ++ windowColumnsLineage
        } else {
          parentColumnsLineage.map {
            case (k, _) if windowColumnsLineage.contains(k) =>
              k -> windowColumnsLineage(k)
            case (k, attrs) =>
              k -> AttributeSet(attrs.flatten(attr =>
                windowColumnsLineage.getOrElse(attr, AttributeSet(attr))))
          }
        }
        p.children.map(extractColumnsLineage(_, nextColumnsLineage)).reduce(mergeColumnsLineage)

      case p: Join =>
        p.joinType match {
          case LeftSemi | LeftAnti =>
            extractColumnsLineage(p.left, parentColumnsLineage)
          case _ =>
            p.children.map(extractColumnsLineage(_, parentColumnsLineage))
              .reduce(mergeColumnsLineage)
        }

      case p: Union =>
        val childrenColumnsLineage =
          // support for the multi-insert statement
          if (p.output.isEmpty) {
            p.children
              .map(extractColumnsLineage(_, ListMap[Attribute, AttributeSet]()))
              .reduce(mergeColumnsLineage)
          } else {
            // merge all children in to one derivedColumns
            val childrenUnion =
              p.children.map(extractColumnsLineage(_, ListMap[Attribute, AttributeSet]())).map(
                _.values).reduce {
                (left, right) =>
                  left.zip(right).map(attr => attr._1 ++ attr._2)
              }
            ListMap(p.output.zip(childrenUnion): _*)
          }
        joinColumnsLineage(parentColumnsLineage, childrenColumnsLineage)

      case p: LogicalRelation if p.catalogTable.nonEmpty =>
        val tableName = p.catalogTable.get.qualifiedName
        joinRelationColumnLineage(parentColumnsLineage, p.output, Seq(tableName))

      case p: HiveTableRelation =>
        val tableName = p.tableMeta.qualifiedName
        joinRelationColumnLineage(parentColumnsLineage, p.output, Seq(tableName))

      case p: DataSourceV2ScanRelation =>
        val tableName = p.name
        joinRelationColumnLineage(parentColumnsLineage, p.output, Seq(tableName))

      // For creating the view from v2 table, the logical plan of table will
      // be the `DataSourceV2Relation` not the `DataSourceV2ScanRelation`.
      // because the view from the table is not going to read it.
      case p: DataSourceV2Relation =>
        val tableName = p.name
        joinRelationColumnLineage(parentColumnsLineage, p.output, Seq(tableName))

      case p: LocalRelation =>
        joinRelationColumnLineage(parentColumnsLineage, p.output, Seq(LOCAL_TABLE_IDENTIFIER))

      case _: OneRowRelation =>
        parentColumnsLineage.map {
          case (k, attrs) =>
            k -> AttributeSet(attrs.map {
              case attr
                  if attr.qualifier.nonEmpty && attr.qualifier.last.equalsIgnoreCase(
                    SUBQUERY_COLUMN_IDENTIFIER) =>
                attr.withQualifier(attr.qualifier.init)
              case attr => attr
            })
        }

      case p: View =>
        if (!p.isTempView && SparkContextHelper.getConf(
            LineageConf.SKIP_PARSING_PERMANENT_VIEW_ENABLED)) {
          val viewName = p.desc.qualifiedName
          joinRelationColumnLineage(parentColumnsLineage, p.output, Seq(viewName))
        } else {
          val viewColumnsLineage =
            extractColumnsLineage(p.child, ListMap[Attribute, AttributeSet]())
          mergeRelationColumnLineage(parentColumnsLineage, p.output, viewColumnsLineage)
        }

      case p: InMemoryRelation =>
        // get logical plan from cachedPlan
        val cachedTableLogical = findSparkPlanLogicalLink(Seq(p.cacheBuilder.cachedPlan))
        cachedTableLogical match {
          case Some(logicPlan) =>
            val relationColumnLineage =
              extractColumnsLineage(logicPlan, ListMap[Attribute, AttributeSet]())
            mergeRelationColumnLineage(parentColumnsLineage, p.output, relationColumnLineage)
          case _ =>
            joinRelationColumnLineage(
              parentColumnsLineage,
              p.output,
              p.cacheBuilder.tableName.toSeq)
        }

      case p if p.children.isEmpty => ListMap[Attribute, AttributeSet]()

      case p =>
        p.children.map(extractColumnsLineage(_, parentColumnsLineage)).reduce(mergeColumnsLineage)
    }
  }

  private def getPlanField[T](field: String, plan: LogicalPlan): T = {
    getFieldVal[T](plan, field)
  }

  private def getCurrentPlanField[T](curPlan: LogicalPlan, field: String): T = {
    getFieldVal[T](curPlan, field)
  }

  private def getPlanMethod[T](name: String, plan: LogicalPlan): T = {
    getMethod[T](plan, name)
  }

  private def getQuery(plan: LogicalPlan): LogicalPlan = {
    getPlanField[LogicalPlan]("query", plan)
  }

  private def getFieldVal[T](o: Any, name: String): T = {
    Try {
      val field = o.getClass.getDeclaredField(name)
      field.setAccessible(true)
      field.get(o)
    } match {
      case Success(value) => value.asInstanceOf[T]
      case Failure(e) =>
        val candidates = o.getClass.getDeclaredFields.map(_.getName).mkString("[", ",", "]")
        throw new RuntimeException(s"$name not in $candidates", e)
    }
  }

  private def getMethod[T](o: Any, name: String): T = {
    Try {
      val method = o.getClass.getDeclaredMethod(name)
      method.invoke(o)
    } match {
      case Success(value) => value.asInstanceOf[T]
      case Failure(e) =>
        val candidates = o.getClass.getDeclaredMethods.map(_.getName).mkString("[", ",", "]")
        throw new RuntimeException(s"$name not in $candidates", e)
    }
  }

}

case class SparkSQLLineageParseHelper(sparkSession: SparkSession) extends LineageParser
  with Logging {

  def transformToLineage(
      executionId: Long,
      plan: LogicalPlan): Option[Lineage] = {
    Try(parse(plan)).recover {
      case e: Exception =>
        logWarning(s"Extract Statement[$executionId] columns lineage failed.", e)
        throw e
    }.toOption
  }

}
