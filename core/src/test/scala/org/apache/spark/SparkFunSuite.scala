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

package org.apache.spark

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.{Locale, TimeZone}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import org.apache.commons.io.FileUtils
import org.apache.logging.log4j._
import org.apache.logging.log4j.core.{LogEvent, Logger, LoggerContext}
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, BeforeAndAfterEach, Failed, Outcome}
import org.scalatest.funsuite.AnyFunSuite

import org.apache.spark.deploy.LocalSparkCluster
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.Tests.IS_TESTING
import org.apache.spark.util.{AccumulatorContext, Utils}

/**
 * Base abstract class for all unit tests in Spark for handling common functionality.
 *
 * Thread audit happens normally here automatically when a new test suite created.
 * The only prerequisite for that is that the test class must extend [[SparkFunSuite]].
 *
 * It is possible to override the default thread audit behavior by setting enableAutoThreadAudit
 * to false and manually calling the audit methods, if desired. For example:
 *
 * class MyTestSuite extends SparkFunSuite {
 *
 *   override val enableAutoThreadAudit = false
 *
 *   protected override def beforeAll(): Unit = {
 *     doThreadPreAudit()
 *     super.beforeAll()
 *   }
 *
 *   protected override def afterAll(): Unit = {
 *     super.afterAll()
 *     doThreadPostAudit()
 *   }
 * }
 */
abstract class SparkFunSuite
  extends AnyFunSuite
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with ThreadAudit
  with Logging {
// scalastyle:on

  // Initialize the logger forcibly to let the logger log timestamp
  // based on the local time zone depending on environments.
  // The default time zone will be set to America/Los_Angeles later
  // so this initialization is necessary here.
  log

  // Timezone is fixed to America/Los_Angeles for those timezone sensitive tests (timestamp_*)
  TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
  // Add Locale setting
  Locale.setDefault(Locale.US)

  protected val enableAutoThreadAudit = true

  protected override def beforeAll(): Unit = {
    System.setProperty(IS_TESTING.key, "true")
    if (enableAutoThreadAudit) {
      doThreadPreAudit()
    }
    super.beforeAll()
  }

  protected override def afterAll(): Unit = {
    try {
      // Avoid leaking map entries in tests that use accumulators without SparkContext
      AccumulatorContext.clear()
    } finally {
      super.afterAll()
      if (enableAutoThreadAudit) {
        doThreadPostAudit()
      }
    }
  }

  // helper function
  protected final def getTestResourceFile(file: String): File = {
    new File(getClass.getClassLoader.getResource(file).getFile)
  }

  protected final def getTestResourcePath(file: String): String = {
    getTestResourceFile(file).getCanonicalPath
  }

  protected final def copyAndGetResourceFile(fileName: String, suffix: String): File = {
    val url = Thread.currentThread().getContextClassLoader.getResource(fileName)
    // To avoid illegal accesses to a resource file inside jar
    // (URISyntaxException might be thrown when accessing it),
    // copy it into a temporary one for accessing it from the dependent module.
    val file = File.createTempFile("test-resource", suffix)
    file.deleteOnExit()
    FileUtils.copyURLToFile(url, file)
    file
  }

  /**
   * Get a Path relative to the root project. It is assumed that a spark home is set.
   */
  protected final def getWorkspaceFilePath(first: String, more: String*): Path = {
    if (!(sys.props.contains("spark.test.home") || sys.env.contains("SPARK_HOME"))) {
      fail("spark.test.home or SPARK_HOME is not set.")
    }
    val sparkHome = sys.props.getOrElse("spark.test.home", sys.env("SPARK_HOME"))
    java.nio.file.Paths.get(sparkHome, first +: more: _*)
  }

  /**
   * Note: this method doesn't support `BeforeAndAfter`. You must use `BeforeAndAfterEach` to
   * set up and tear down resources.
   */
  def testRetry(s: String, n: Int = 2)(body: => Unit): Unit = {
    test(s) {
      retry(n) {
        body
      }
    }
  }

  /**
   * Note: this method doesn't support `BeforeAndAfter`. You must use `BeforeAndAfterEach` to
   * set up and tear down resources.
   */
  def retry[T](n: Int)(body: => T): T = {
    if (this.isInstanceOf[BeforeAndAfter]) {
      throw new UnsupportedOperationException(
        s"testRetry/retry cannot be used with ${classOf[BeforeAndAfter]}. " +
          s"Please use ${classOf[BeforeAndAfterEach]} instead.")
    }
    retry0(n, n)(body)
  }

  @tailrec private final def retry0[T](n: Int, n0: Int)(body: => T): T = {
    try body
    catch { case e: Throwable =>
      if (n > 0) {
        logWarning(e.getMessage, e)
        logInfo(s"\n\n===== RETRY #${n0 - n + 1} =====\n")
        // Reset state before re-attempting in order so that tests which use patterns like
        // LocalSparkContext to clean up state can work correctly when retried.
        afterEach()
        beforeEach()
        retry0(n-1, n0)(body)
      }
      else throw e
    }
  }

  protected def logForFailedTest(): Unit = {
    LocalSparkCluster.get.foreach { localCluster =>
      val workerLogfiles = localCluster.workerLogfiles
      if (workerLogfiles.nonEmpty) {
        logInfo("\n\n===== EXTRA LOGS FOR THE FAILED TEST\n")
        workerLogfiles.foreach { logFile =>
          logInfo(s"\n----- Logfile: ${logFile.getAbsolutePath()}")
          logInfo(FileUtils.readFileToString(logFile, "UTF-8"))
        }
      }
    }
  }

  /**
   * Log the suite name and the test name before and after each test.
   *
   * Subclasses should never override this method. If they wish to run
   * custom code before and after each test, they should mix in the
   * {{org.scalatest.BeforeAndAfter}} trait instead.
   */
  final protected override def withFixture(test: NoArgTest): Outcome = {
    val testName = test.text
    val suiteName = this.getClass.getName
    val shortSuiteName = suiteName.replaceAll("org.apache.spark", "o.a.s")
    try {
      logInfo(s"\n\n===== TEST OUTPUT FOR $shortSuiteName: '$testName' =====\n")
      val outcome = test()
      outcome match {
        case _: Failed =>
          logForFailedTest()
        case _ =>
      }
      outcome
    } finally {
      logInfo(s"\n\n===== FINISHED $shortSuiteName: '$testName' =====\n")
    }
  }

  /**
   * Creates a temporary directory, which is then passed to `f` and will be deleted after `f`
   * returns.
   */
  protected def withTempDir(f: File => Unit): Unit = {
    val dir = Utils.createTempDir()
    try f(dir) finally {
      Utils.deleteRecursively(dir)
    }
  }

  /**
   * Creates a temporary directory containing a secret file, which is then passed to `f` and
   * will be deleted after `f` returns.
   */
  protected def withSecretFile(contents: String = "test-secret")(f: File => Unit): Unit = {
    val secretDir = Utils.createTempDir("temp-secrets")
    val secretFile = new File(secretDir, "temp-secret.txt")
    Files.write(secretFile.toPath, contents.getBytes(UTF_8))
    try f(secretFile) finally {
      Utils.deleteRecursively(secretDir)
    }
  }

  /**
   * Adds a log appender and optionally sets a log level to the root logger or the logger with
   * the specified name, then executes the specified function, and in the end removes the log
   * appender and restores the log level if necessary.
   */
  protected def withLogAppender(
      appender: AbstractAppender,
      loggerNames: Seq[String] = Seq.empty,
      level: Option[Level] = None)(
      f: => Unit): Unit = {
    val loggers = if (loggerNames.nonEmpty) {
      loggerNames.map(LogManager.getLogger)
    } else {
      Seq(LogManager.getRootLogger)
    }
    if (loggers.size == 0) {
      throw new SparkException(s"Cannot get any logger to add the appender")
    }
    val restoreLevels = loggers.map(_.getLevel)
    loggers.foreach { l =>
      val logger = l.asInstanceOf[Logger]
      logger.addAppender(appender)
      appender.start()
      if (level.isDefined) {
        logger.setLevel(level.get)
        logger.get().setLevel(level.get)
        LogManager.getContext(false).asInstanceOf[LoggerContext].updateLoggers()
      }
    }
    try f finally {
      loggers.foreach(_.asInstanceOf[Logger].removeAppender(appender))
      appender.stop()
      if (level.isDefined) {
        loggers.zipWithIndex.foreach { case (logger, i) =>
          logger.asInstanceOf[Logger].setLevel(restoreLevels(i))
          logger.asInstanceOf[Logger].get().setLevel(restoreLevels(i))
        }
      }
    }
  }

  /**
   * Checks an exception with an error class against expected results.
   * @param exception     The exception to check
   * @param errorClass    The expected error class identifying the error
   * @param errorSubClass Optional the expected subclass, None if not given
   * @param sqlState      Optional the expected SQLSTATE, not verified if not supplied
   * @param parameters    A map of parameter names and values. The names are as defined
   *                      in the error-classes file.
   * @param matchPVals    Optionally treat the parameters value as regular expression pattern.
   *                      false if not supplied.
   */
  protected def checkError(
      exception: SparkThrowable,
      errorClass: String,
      errorSubClass: Option[String] = None,
      sqlState: Option[String] = None,
      parameters: Map[String, String] = Map.empty,
      matchPVals: Boolean = false): Unit = {
    assert(exception.getErrorClass === errorClass)
    if (exception.getErrorSubClass != null) {
      assert(errorSubClass.isDefined)
      assert(exception.getErrorSubClass === errorSubClass.get)
    }
    sqlState.foreach(state => assert(exception.getSqlState === state))
    val expectedParameters = (exception.getParameterNames zip exception.getMessageParameters).toMap
    if (matchPVals == true) {
      assert(expectedParameters.size === parameters.size)
      expectedParameters.foreach(
        exp => {
          val parm = parameters.getOrElse(exp._1,
            throw new IllegalArgumentException("Missing parameter" + exp._1))
          if (!exp._2.matches(parm)) {
            throw new IllegalArgumentException("(" + exp._1 + ", " + exp._2 +
              ") does not match: " + parm)
          }
        }
      )
    } else {
      assert(expectedParameters === parameters)
    }
  }

  protected def checkError(
      exception: SparkThrowable,
      errorClass: String,
      errorSubClass: String,
      sqlState: String,
      parameters: Map[String, String]): Unit =
    checkError(exception, errorClass, Some(errorSubClass), Some(sqlState), parameters)

  protected def checkError(
      exception: SparkThrowable,
      errorClass: String,
      sqlState: String,
      parameters: Map[String, String]): Unit =
    checkError(exception, errorClass, None, Some(sqlState), parameters)

  class LogAppender(msg: String = "", maxEvents: Int = 1000)
      extends AbstractAppender("logAppender", null, null, true, Property.EMPTY_ARRAY) {
    private val _loggingEvents = new ArrayBuffer[LogEvent]()
    private var _threshold: Level = Level.INFO

    override def append(loggingEvent: LogEvent): Unit = loggingEvent.synchronized {
      val copyEvent = loggingEvent.toImmutable
      if (copyEvent.getLevel.isMoreSpecificThan(_threshold)) {
        _loggingEvents.synchronized {
          if (_loggingEvents.size >= maxEvents) {
            val loggingInfo = if (msg == "") "." else s" while logging $msg."
            throw new IllegalStateException(
              s"Number of events reached the limit of $maxEvents$loggingInfo")
          }
          _loggingEvents.append(copyEvent)
        }
      }
    }

    def setThreshold(threshold: Level): Unit = {
      _threshold = threshold
    }

    def loggingEvents: ArrayBuffer[LogEvent] = _loggingEvents.synchronized {
      _loggingEvents.filterNot(_ == null)
    }
  }
}
