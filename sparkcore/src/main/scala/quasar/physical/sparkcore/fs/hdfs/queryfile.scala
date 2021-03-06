/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.sparkcore.fs.hdfs

import quasar.Predef._
import quasar.Data
import quasar.DataCodec
import quasar.physical.sparkcore.fs.queryfile.Input
import quasar.contrib.pathy._
import quasar.fs.FileSystemError
import quasar.fs.FileSystemErrT
import quasar.fs.FileSystemError._
import quasar.fs.PathError._
import quasar.contrib.pathy._

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.OutputStream

import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.util.Progressable
import pathy.Path._
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import org.apache.spark._
import org.apache.spark.rdd._

class queryfile(fileSystem: Task[FileSystem]) {

  private def toPath(apath: APath): Task[Path] = Task.delay {
    new Path(posixCodec.unsafePrintPath(apath))
  }

  def fromFile(sc: SparkContext, file: AFile): Task[RDD[String]] = fileSystem.map { hdfs =>
    val pathStr = posixCodec.unsafePrintPath(file)
    val host = hdfs.getUri().getHost()
    val port = hdfs.getUri().getPort()
    val url = s"hdfs://$host:$port$pathStr"
    hdfs.close()
    sc.textFile(url)
  }

  def store(rdd: RDD[Data], out: AFile): Task[Unit] = for {
    path <- toPath(out)
    hdfs <- fileSystem
  } yield {
    val os: OutputStream = hdfs.create(path, new Progressable() {
      override def progress(): Unit = {}
    })
    val bw = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"))

    rdd.map(data => DataCodec.render(data)(DataCodec.Precise)).collect().foreach {
      case \/-(v) =>
        bw.write(v)
        bw.newLine()
      case -\/(der) =>
        bw.write(s"encoding error: ${der.message}")
        bw.newLine()
    }
    bw.close()
    hdfs.close()
  }

  def fileExists(f: AFile): Task[Boolean] = for {
    path <- toPath(f)
    hdfs <- fileSystem
  } yield {
    val exists = hdfs.exists(path)
    hdfs.close()
    exists
  }

  def listContents(d: ADir): FileSystemErrT[Task, Set[PathSegment]] = EitherT(for {
    path <- toPath(d)
    hdfs <- fileSystem
  } yield {
    val result = if(hdfs.exists(path)) {
      hdfs.listStatus(path).toSet.map {
        case file if file.isFile() => FileName(file.getPath().getName()).right[DirName]
        case directory => DirName(directory.getPath().getName()).left[FileName]
      }.right[FileSystemError]
    } else pathErr(pathNotFound(d)).left[Set[PathSegment]]
    hdfs.close
    result
  })

  def readChunkSize: Int = 5000

  def input: Input =
    Input(fromFile _, store _, fileExists _, listContents _, readChunkSize _)
}

object queryfile {
  def input(fileSystem: Task[FileSystem]): Input = new queryfile(fileSystem).input
}
