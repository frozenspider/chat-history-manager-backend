package org.fs.chm.utility

import java.io.File
import java.nio.file.Files

import scala.annotation.tailrec

import org.slf4s.Logging

object IoUtils extends Logging {
  def tryWith[A <: AutoCloseable, B](alloc: => A)(code: A => B): B = {
    val a = alloc
    try {
      code(a)
    } finally {
      closeWithoutThrowing(a);
    }
  }

  def closeWithoutThrowing(c: AutoCloseable): Unit = {
    try {
      c.close()
    } catch {
      case ex: Throwable => log.warn("Failed to close resource", ex)
    }
  }

  /**
   * Given a files map, copy key files to value files, not overwriting them.
   * @return `(NotFound, AlreadyExist)`
   */
  def copyAll(filesMap: Map[File, File]): (IndexedSeq[File], IndexedSeq[File]) = {
    log.info(s"Copying ${filesMap.size} files")
    var notFound      = IndexedSeq.empty[File]
    var alreadyExists = IndexedSeq.empty[File]
    filesMap.values.toSet.map((f: File) => f.getParentFile).foreach(_.mkdirs())
    for ((from, to) <- filesMap.par) {
      if (from.exists()) {
        if (!to.exists()) {
          Files.copy(from.toPath, to.toPath)
        } else {
          alreadyExists = alreadyExists :+ to
        }
      } else {
        notFound = notFound :+ from
      }
    }
    (notFound, alreadyExists)
  }

  implicit class RichFile(f: File) {
    @tailrec
    final def existingDir: File = {
      if (f.exists && f.isDirectory) f
      else f.getParentFile.existingDir
    }
  }
}
