/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Johannes Rudolph
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.virtualvoid.optimizer

import java.io.{ File, FileOutputStream, InputStream }
import java.net.URL
import java.util.concurrent.Callable

import org.apache.ivy.util.CopyProgressListener
import org.apache.ivy.util.url.URLHandler.URLInfo
import org.apache.ivy.util.url.{ URLHandler, URLHandlerRegistry }
import sbt.{ State, Keys, Command, Action, seq }
import xsbti._

import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

trait DownloadListener {
  def downloadOccurred(download: NetworkAccess): Unit
}

object IvyDownloadReporter {
  val listener = new ThreadLocal[DownloadListener]

  def withListener[T](l: DownloadListener)(body: ⇒ T): T = {
    val oldListener = listener.get
    listener.set(l)
    try body
    finally listener.set(oldListener)
  }

  val reportDownloadsAction: State ⇒ State = {
    state ⇒
      URLHandlerRegistry.getDefault match {
        case i: IvyDownloadReporter ⇒ i.printTracingReport()
        case _                      ⇒
      }
      state
  }

  val reportDownloadsCommand: Command = Command.command("report-downloads")(reportDownloadsAction)

  def install() = seq(
    Keys.initialize <<= (Keys.initialize, Keys.ivySbt) { (_, ivy) ⇒
      def replaceDefault(default: URLHandler): Unit =
        default match {
          case i: IvyDownloadReporter ⇒
            println("Download reporter already installed")
          case x if x.getClass.getSimpleName == "IvyDownloadReporter" ⇒ // fix interactive recompilation
            replaceDefault(x.asInstanceOf[Product1[URLHandler]]._1)
          case x ⇒
            println(s"Download reporter installed instead of default ${x.getClass.getSimpleName}")
            URLHandlerRegistry.setDefault(new IvyDownloadReporter(x))
        }

      replaceDefault(URLHandlerRegistry.getDefault)
    },
    Keys.commands += reportDownloadsCommand)

  def printTracingReport(traceEntries: Seq[NetworkAccess]): Unit = {
    def totalBytes(entries: Seq[NetworkAccess]): Long =
      entries.collect {
        case NetworkAccess(_, d: DownloadURL, _, _) ⇒ d.size
      }.sum
    def extension(url: URL): String = {
      val path = url.getPath
      val idx = path.lastIndexOf(".")
      path.substring(idx + 1)
    }
    def histo[T](criterium: NetworkAccess ⇒ T): Seq[(Int, Long, Long, T)] =
      traceEntries.groupBy(criterium).map {
        case (t, entries) ⇒ (entries.size, entries.map(_.lastedMillis).sum, totalBytes(entries), t)
      }.toSeq.sortBy(-_._2)
    def histoReport[T](criteriumName: String)(criterium: NetworkAccess ⇒ T): Unit = {
      println(s"By $criteriumName")
      val h = histo(criterium).take(10)
      h.foreach {
        case (n, millis, bytes, crit) ⇒ println(f"$millis%6d ms $n%4d ${bytes / 1000000}%6.2f MB $crit%s")
      }
    }

    histoReport("Extension")(a ⇒ extension(a.url))
    histoReport("Scheme")(a ⇒ a.url.getProtocol)
    histoReport("Hosts")(a ⇒ a.url.getHost)
    histoReport("Kind")(a ⇒ a.kind.productPrefix)

    val fruitless = traceEntries.collect {
      case n @ NetworkAccess(_, GetURLInfo(false), _, _) ⇒ n
    }
    println(f"Time spent on fruitless getURLInfo calls: ${fruitless.map(_.lastedMillis).sum}%6d ms")
    fruitless.groupBy(_.url.getHost).toSeq.map {
      case (host, entries) ⇒ (host, entries.map(_.lastedMillis).sum)
    }.sortBy(-_._2).foreach {
      case (host, millis) ⇒ println(f"$millis%6d $host")
    }

    val downloads = traceEntries.collect {
      case n @ NetworkAccess(_, _: DownloadURL, _, _) ⇒ n
    }
    val mbDownloaded = totalBytes(downloads).toDouble / 1000000
    val secondsDownloading = downloads.map(_.lastedMillis).sum.toDouble / 1000
    println(f"Total downloaded: $mbDownloaded%6.2f MB in $secondsDownloading%6.2f s ${mbDownloaded / secondsDownloading}%6.2f MB / s = ${mbDownloaded / secondsDownloading * 8}%3.0f MBit/s")
  }

}
class IvyDownloadReporter(underlying: URLHandler) extends URLHandler with Product1[URLHandler] {
  // common supertype across changing ClassLoaders when using `reload`
  def _1: URLHandler = underlying
  def canEqual(that: Any): Boolean = false

  var traceEntries: Seq[NetworkAccess] = Vector.empty
  var totalMillisSpent = 0L

  def report(download: NetworkAccess): Unit = Option(IvyDownloadReporter.listener.get).foreach(_.downloadOccurred(download))
  def timeAndReport[T](url: URL, kind: DownloadKind)(body: ⇒ T): T = timeAndReportWithResult(url)(body)(_ ⇒ kind)
  def timeAndReportWithResult[T](url: URL)(body: ⇒ T)(kindCons: T ⇒ DownloadKind): T = {
    val start = System.nanoTime()

    val res = Try(body)

    val kind = res match {
      case Success(r) ⇒ kindCons(r)
      case Failure(_) ⇒ Failed
    }
    val end = System.nanoTime()
    val access = NetworkAccess(url, kind, start, end)
    totalMillisSpent += access.lastedMillis
    traceEntries :+= access
    if (url.getProtocol.startsWith("http")) report(access)
    res.get
  }

  def isReachable(url: URL): Boolean = underlying.isReachable(url)
  def getURLInfo(url: URL): URLInfo =
    timeAndReportWithResult(url)(underlying.getURLInfo(url))(info ⇒ GetURLInfo(info.isReachable))
  def getURLInfo(url: URL, timeout: Int): URLInfo =
    timeAndReportWithResult(url)(underlying.getURLInfo(url, timeout))(info ⇒ GetURLInfo(info.isReachable))
  def openStream(url: URL): InputStream = timeAndReport(url, OpenStreamURL)(underlying.openStream(url))
  def download(src: URL, dest: File, l: CopyProgressListener): Unit = timeAndReportWithResult(src)(underlying.download(src, dest, l))(_ ⇒ DownloadURL(dest, dest.length()))

  def setRequestMethod(requestMethod: Int): Unit = underlying.setRequestMethod(requestMethod)
  def upload(src: File, dest: URL, l: CopyProgressListener): Unit = underlying.upload(src, dest, l)
  def getLastModified(url: URL): Long = underlying.getLastModified(url)
  def getLastModified(url: URL, timeout: Int): Long = underlying.getLastModified(url, timeout)
  def isReachable(url: URL, timeout: Int): Boolean = underlying.isReachable(url, timeout)
  def getContentLength(url: URL): Long = underlying.getContentLength(url)
  def getContentLength(url: URL, timeout: Int): Long = underlying.getContentLength(url, timeout)

  def printTracingReport(): Unit = IvyDownloadReporter.printTracingReport(traceEntries)
}