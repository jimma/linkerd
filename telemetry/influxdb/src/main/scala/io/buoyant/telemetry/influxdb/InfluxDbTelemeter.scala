package io.buoyant.telemetry.influxdb

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.{Awaitable, Closable, Future}
import io.buoyant.admin.Admin
import io.buoyant.telemetry.{Metric, MetricsTree, Telemeter}

/**
 * This telemeter exposes metrics data in the InfluxDb LINE format, served on
 * an admin endpoint.  It does not provide a StatsReceiver or Tracer.  It reads
 * histogram summaries directly off of the MetricsTree and assumes that stats
 * are being snapshotted at some appropriate interval.
 */
class InfluxDbTelemeter(metrics: MetricsTree) extends Telemeter with Admin.WithHandlers {

  private[influxdb] val handler = Service.mk { request: Request =>
    val response = Response()
    response.version = request.version
    response.mediaType = MediaType.Txt
    val sb = new StringBuilder()
    val host = request.host.getOrElse("none")
    writeMetrics(metrics, sb, Nil, Seq("host" -> host))
    response.contentString = sb.toString
    Future.value(response)
  }

  val adminHandlers: Seq[Admin.Handler] = Seq(
    Admin.Handler("/admin/metrics/influxdb", handler)
  )

  val stats = NullStatsReceiver
  def tracer = NullTracer
  def run(): Closable with Awaitable[Unit] = Telemeter.nopRun

  private[this] val disallowedChars = "[^a-zA-Z0-9:]".r
  private[this] def escapeKey(key: String) = disallowedChars.replaceAllIn(key, "_")

  private[this] val first: ((String, String)) => String = _._1
  private[this] def formatLabels(labels: Seq[(String, String)]): String =
    labels.sortBy(first).map {
      case (k, v) =>
        s"""$k=$v"""
    }.mkString(",")

  private[this] def labelExists(labels: Seq[(String, String)], name: String) =
    labels.toIterator.map(first).contains(name)

  private[this] def writeMetrics(
    tree: MetricsTree,
    sb: StringBuilder,
    prefix0: Seq[String],
    tags0: Seq[(String, String)]
  ): Unit = {
    // TODO: share with prometheus telemeter?
    // Re-write elements out of the prefix into labels
    val (prefix1, tags1) = prefix0 match {
      case Seq("rt", router) if !labelExists(tags0, "rt") =>
        (Seq("rt"), tags0 :+ ("rt" -> router))
      case Seq("rt", "dst", "path", path) if !labelExists(tags0, "dst_path") =>
        (Seq("rt", "dst_path"), tags0 :+ ("dst_path" -> path))
      case Seq("rt", "dst", "id", id) if !labelExists(tags0, "dst_id") =>
        (Seq("rt", "dst_id"), tags0 :+ ("dst_id" -> id))
      case Seq("rt", "dst_id", "path", path) if !labelExists(tags0, "dst_path") =>
        (Seq("rt", "dst_id", "dst_path"), tags0 :+ ("dst_path" -> path))
      case Seq("rt", "srv", srv) if !labelExists(tags0, "srv") =>
        (Seq("rt", "srv"), tags0 :+ ("srv" -> srv))
      case _ => (prefix0, tags0)
    }

    // gather all metrics directly under a common parent
    val fields =
      tree.children.toSeq.flatMap {
        case (name, child) =>
          // write metrics deeper in the tree (side effect)
          writeMetrics(child, sb, prefix1 :+ name, tags1)

          child.metric match {
            case c: Metric.Counter => Seq(name -> c.get.toString)
            case g: Metric.Gauge => Seq(name -> g.get.toString)
            case s: Metric.Stat =>
              val summary = s.snapshottedSummary
              if (summary != null) {
                Seq(
                  name + "_count" -> summary.count.toString,
                  name + "_sum" -> summary.sum.toString,
                  name + "_avg" -> summary.avg.toString,
                  name + "_min" -> summary.min.toString,
                  name + "_max" -> summary.max.toString,
                  name + "_p50" -> summary.p50.toString,
                  name + "_p90" -> summary.p90.toString,
                  name + "_p95" -> summary.p95.toString,
                  name + "_p99" -> summary.p99.toString,
                  name + "_p999" -> summary.p9990.toString,
                  name + "_p9999" -> summary.p9999.toString
                )
              } else {
                None
              }
            case _ => None
          }
      }

    // write sibling metrics as fields in a single measurement
    if (fields.nonEmpty) {
      sb.append(escapeKey(prefix1.mkString(":")))
      if (tags1.nonEmpty) {
        sb.append(",")
        sb.append(formatLabels(tags1))
      }
      sb.append(" ")
      sb.append(formatLabels(fields))
      val _ = sb.append("\n")
    }
  }
}
