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
package org.apache.spark.sql.connect.client.util

import java.io.File
import java.nio.file.{Files, Paths}

import scala.util.Properties.versionNumberString

import org.scalatest.Assertions.fail

import org.apache.spark.util.Utils

object IntegrationTestUtils {

  // System properties used for testing and debugging
  private val DEBUG_SC_JVM_CLIENT = "spark.debug.sc.jvm.client"
  // Enable this flag to print all client debug log + server logs to the console
  private[connect] val isDebug = System.getProperty(DEBUG_SC_JVM_CLIENT, "false").toBoolean

  private[sql] lazy val scalaVersion = {
    versionNumberString.split('.') match {
      case Array(major, minor, _*) => major + "." + minor
      case _ => versionNumberString
    }
  }

  private[sql] lazy val scalaDir = s"scala-$scalaVersion"

  private[sql] lazy val sparkHome: String = {
    if (!(sys.props.contains("spark.test.home") || sys.env.contains("SPARK_HOME"))) {
      fail("spark.test.home or SPARK_HOME is not set.")
    }
    sys.props.getOrElse("spark.test.home", sys.env("SPARK_HOME"))
  }

  private[connect] def debugConfigs: Seq[String] = {
    val log4j2 = s"$sparkHome/connector/connect/client/jvm/src/test/resources/log4j2.properties"
    if (isDebug) {
      Seq(
        // Enable to see the server plan change log
        // "--conf",
        // "spark.sql.planChangeLog.level=WARN",

        // Enable to see the server grpc received
        // "--conf",
        // "spark.connect.grpc.interceptor.classes=" +
        //  "org.apache.spark.sql.connect.service.LoggingInterceptor",

        // Redirect server log into console
        "--conf",
        s"spark.driver.extraJavaOptions=-Dlog4j.configuration=$log4j2")
    } else Seq.empty
  }

  // Log server start stop debug info into console
  // scalastyle:off println
  private[connect] def debug(msg: String): Unit = if (isDebug) println(msg)
  // scalastyle:on println
  private[connect] def debug(error: Throwable): Unit = if (isDebug) error.printStackTrace()

  private[sql] lazy val isSparkHiveJarAvailable: Boolean = {
    val filePath = s"$sparkHome/assembly/target/$scalaDir/jars/" +
      s"spark-hive_$scalaVersion-${org.apache.spark.SPARK_VERSION}.jar"
    Files.exists(Paths.get(filePath))
  }

  private[sql] def cleanUpHiveClassesDirIfNeeded(): Unit = {
    def delete(f: File): Unit = {
      if (f.exists()) {
        Utils.deleteRecursively(f)
      }
    }
    delete(new File(s"$sparkHome/sql/hive/target/$scalaDir/classes"))
    delete(new File(s"$sparkHome/sql/hive/target/$scalaDir/test-classes"))
  }

  /**
   * Find a jar in the Spark project artifacts. It requires a build first (e.g. build/sbt package,
   * build/mvn clean install -DskipTests) so that this method can find the jar in the target
   * folders.
   *
   * @return
   *   the jar
   */
  private[sql] def findJar(
      path: String,
      sbtName: String,
      mvnName: String,
      test: Boolean = false): File = {
    val jar = tryFindJar(path, sbtName, mvnName, test).getOrElse(
      throw new RuntimeException(
        s"Failed to find the jar inside folder: ${getTargetFilePath(path)}"))
    debug("Using jar: " + jar.getCanonicalPath)
    jar
  }

  private[sql] def tryFindJar(
      path: String,
      sbtName: String,
      mvnName: String,
      test: Boolean = false): Option[File] = {
    val targetDir = getTargetFilePath(path).toFile
    assert(
      targetDir.exists(),
      s"Fail to locate the target folder: '${targetDir.getCanonicalPath}'. " +
        s"SPARK_HOME='${new File(sparkHome).getCanonicalPath}'. " +
        "Make sure the spark project jars has been built (e.g. using build/sbt package)" +
        "and the env variable `SPARK_HOME` is set correctly.")
    val suffix = if (test) "-tests.jar" else ".jar"
    // It is possible there are more than one: one built by maven, and another by SBT,
    // Return the first one found.
    recursiveListFiles(targetDir).find { f =>
      // SBT jar
      (f.getParentFile.getName == scalaDir &&
        f.getName.startsWith(sbtName) && f.getName.endsWith(suffix)) ||
      // Maven Jar
      (f.getParent.endsWith("target") &&
        f.getName.startsWith(mvnName) &&
        f.getName.endsWith(s"${org.apache.spark.SPARK_VERSION}$suffix"))
    }
  }

  private def getTargetFilePath(path: String): java.nio.file.Path = {
    Paths.get(sparkHome, path, "target").toAbsolutePath
  }

  private def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles)
  }
}
