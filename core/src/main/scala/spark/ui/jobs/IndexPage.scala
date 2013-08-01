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

package spark.ui.jobs

import java.util.Date

import javax.servlet.http.HttpServletRequest

import scala.collection.mutable.HashSet
import scala.Some
import scala.xml.{NodeSeq, Node}

import spark.scheduler.cluster.TaskInfo
import spark.scheduler.Stage
import spark.storage.StorageLevel
import spark.ui.Page._
import spark.ui.UIUtils._
import spark.Utils

/** Page showing list of all ongoing and recently finished stages */
private[spark] class IndexPage(parent: JobProgressUI) {
  def listener = parent.listener
  val dateFmt = parent.dateFmt

  def render(request: HttpServletRequest): Seq[Node] = {
    val activeStages = listener.activeStages.toSeq
    val completedStages = listener.completedStages.reverse.toSeq
    val failedStages = listener.failedStages.reverse.toSeq
    val now = System.currentTimeMillis()

    var activeTime = 0L
    for (tasks <- listener.stageToTasksActive.values; t <- tasks) {
      activeTime += t.timeRunning(now)
    }

    /** Special table which merges two header cells. */
    def stageTable[T](makeRow: T => Seq[Node], rows: Seq[T]): Seq[Node] = {
      <table class="table table-bordered table-striped table-condensed sortable">
        <thead>
          <th>Stage Id</th>
          <th>Origin</th>
          <th>Submitted</th>
          <th>Duration</th>
          <th colspan="2">Tasks: Complete/Total</th>
          <th>Shuffle Read</th>
          <th>Shuffle Write</th>
          <th>Stored RDD</th>
        </thead>
        <tbody>
          {rows.map(r => makeRow(r))}
        </tbody>
      </table>
    }

    val summary: NodeSeq =
     <div>
       <ul class="unstyled">
          <li>
            <strong>CPU time: </strong>
            {parent.formatDuration(listener.totalTime + activeTime)}
          </li>
         {if (listener.totalShuffleRead > 0)
           <li>
              <strong>Shuffle read: </strong>
              {Utils.memoryBytesToString(listener.totalShuffleRead)}
            </li>
         }
         {if (listener.totalShuffleWrite > 0)
           <li>
              <strong>Shuffle write: </strong>
              {Utils.memoryBytesToString(listener.totalShuffleWrite)}
            </li>
         }
       </ul>
     </div>
    val activeStageTable: NodeSeq = stageTable(stageRow, activeStages)
    val completedStageTable = stageTable(stageRow, completedStages)
    val failedStageTable: NodeSeq = stageTable(stageRow, failedStages)

    val content = summary ++
                  <h2>Active Stages</h2> ++ activeStageTable ++
                  <h2>Completed Stages</h2>  ++ completedStageTable ++
                  <h2>Failed Stages</h2>  ++ failedStageTable

    headerSparkPage(content, parent.sc, "Spark Stages", Jobs)
  }

  def getElapsedTime(submitted: Option[Long], completed: Long): String = {
    submitted match {
      case Some(t) => parent.formatDuration(completed - t)
      case _ => "Unknown"
    }
  }

  def makeProgressBar(started: Int, completed: Int, total: Int): Seq[Node] = {
    val completeWidth = "width: %s%%".format((completed.toDouble/total)*100)
    val startWidth = "width: %s%%".format((started.toDouble/total)*100)

    <div class="progress" style="height: 15px; margin-bottom: 0px">
      <div class="bar" style={completeWidth}></div>
      <div class="bar bar-info" style={startWidth}></div>
    </div>
  }


  def stageRow(s: Stage): Seq[Node] = {
    val submissionTime = s.submissionTime match {
      case Some(t) => dateFmt.format(new Date(t))
      case None => "Unknown"
    }

    val shuffleRead = listener.stageToShuffleRead.getOrElse(s.id, 0L) match {
      case 0 => ""
      case b => Utils.memoryBytesToString(b)
    }
    val shuffleWrite = listener.stageToShuffleWrite.getOrElse(s.id, 0L) match {
      case 0 => ""
      case b => Utils.memoryBytesToString(b)
    }

    val startedTasks = listener.stageToTasksActive.getOrElse(s.id, HashSet[TaskInfo]()).size
    val completedTasks = listener.stageToTasksComplete.getOrElse(s.id, 0)
    val totalTasks = s.numPartitions

    <tr>
      <td style="font-size: small">{s.id}</td>
      <td style="font-size: small"><a href={"/stages/stage?id=%s".format(s.id)}>{s.name}</a></td>
      <td style="font-size: small">{submissionTime}</td>
      <td style="font-size: small">{getElapsedTime(s.submissionTime,
             s.completionTime.getOrElse(System.currentTimeMillis()))}</td>
      <td class="progress-cell">{makeProgressBar(startedTasks, completedTasks, totalTasks)}</td>
      <td style="border-left: 0; text-align: center; font-size: small;">
        {completedTasks} / {totalTasks}
        {listener.stageToTasksFailed.getOrElse(s.id, 0) match {
        case f if f > 0 => "(%s failed)".format(f)
        case _ =>
        }}
      </td>
      <td style="font-size: small">{shuffleRead}</td>
      <td style="font-size: small">{shuffleWrite}</td>
      <td style="font-size: small">{if (s.rdd.getStorageLevel != StorageLevel.NONE) {
             <a href={"/storage/rdd?id=%s".format(s.rdd.id)}>
               {Option(s.rdd.name).getOrElse(s.rdd.id)}
             </a>
          }}
      </td>
    </tr>
  }
}
