package org.fs.chm.loader

import java.io.File
import java.io.FileNotFoundException

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

import cats.effect.IO
import doobie.Transactor
import org.flywaydb.core.Flyway
import org.fs.chm.dao._
import org.fs.utility.StopWatch
import org.h2.jdbcx.JdbcConnectionPool

class H2DataManager extends DataLoader[H2ChatHistoryDao] {
  import org.fs.chm.loader.H2DataManager._
  private val dataFileName = "data." + DefaultExt

  private val options = Seq(
    //"TRACE_LEVEL_FILE=2",
    //"TRACE_LEVEL_SYSTEM_OUT=2",
    "COMPRESS=TRUE",
    "DATABASE_TO_UPPER=false",
    "DEFRAG_ALWAYS=true"
  )
  private val optionsString = options.mkString(";", ";", "")

  def preload(): Seq[Future[_]] = {
    // To preload stuff, we'll create an in-memory DB and drop it
    implicit val ec = ExecutionContext.global
    val f1 = Future {
      StopWatch.measureAndCall {
        val dao = daoFromUrlPath("jdbc:h2:mem:preload1", new File("."))
        try {
          dao.preload()
        } finally {
          dao.close()
        }
      } { (_, t) =>
        log.info(s"H2 DAO preloaded in ${t} ms")
      }
    }
    val f2 = Future {
      StopWatch.measureAndCall {
        val flyway = Flyway.configure.dataSource("jdbc:h2:mem:preload2", "sa", "").load
        flyway.baseline()
      } { (_, t) =>
        log.info(s"Flyway preloaded in ${t} ms")
      }
    }
    Seq(f1, f2)
  }

  /** Path should point to the folder with `data.h2.db` and other stuff */
  override protected def loadDataInner(path: File, createNew: Boolean): H2ChatHistoryDao = {
    val dataDbFile: File = new File(path, dataFileName)
    if (createNew && dataDbFile.exists())
      throw new FileNotFoundException(s"$dataFileName already exists in " + path.getAbsolutePath)
    if (!createNew && !dataDbFile.exists())
      throw new FileNotFoundException(s"$dataFileName not found in " + path.getAbsolutePath)
    // This will create a new DB if data.mv.db does not exist
    val flyway = Flyway.configure.dataSource(dbUrl(dataDbFile), "sa", "").load
    flyway.migrate()
    daoFromFile(dataDbFile)
  }

  private def dbUrl(path: File): String = {
    val innerPath = path.getAbsolutePath.replaceAll("\\." + DefaultExt + "$", "").replace("\\", "/")
    "jdbc:h2:" + innerPath + optionsString
  }

  private def daoFromFile(path: File): H2ChatHistoryDao = {
    daoFromUrlPath(dbUrl(path), path)
  }

  private def daoFromUrlPath(url: String, path: File): H2ChatHistoryDao = {
    Class.forName("org.h2.Driver")
    val connPool = JdbcConnectionPool.create(url, "sa", "")
    val execCxt: ExecutionContext = ExecutionContext.global
    val txctr = Transactor.fromDataSource[IO](connPool, execCxt)
    new H2ChatHistoryDao(dataPathRoot = path.getParentFile, txctr = txctr, () => txctr.kernel.dispose())
  }
}

object H2DataManager {
  val DefaultExt = "mv.db"
}
