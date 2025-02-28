package com.twitter.server.handler

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response, Uri}
import com.twitter.finagle.stats.MetricBuilder.CounterType
import com.twitter.finagle.stats.exp.Expression._
import com.twitter.finagle.stats.exp.{
  ConstantExpression,
  Expression,
  FunctionExpression,
  HistogramExpression,
  MetricExpression,
  NoExpression
}
import com.twitter.finagle.stats.{MetricBuilder, StatsFormatter, metadataScopeSeparator}
import com.twitter.io.Buf
import com.twitter.server.handler.MetricExpressionHandler.{Version, translateToQuery}
import com.twitter.server.util.HttpUtils.newResponse
import com.twitter.server.util.{AdminJsonConverter, MetricSchemaSource}
import com.twitter.util.Future

object MetricExpressionHandler {
  private val Version = 1.1
  private val statsFormatter = StatsFormatter.default

  /**
   * Translate the [[Expression]] object to a single line string which represents generic
   * query language.
   * @param shouldRate If true, we wrap the metric with `rate`
   */
  // exposed for testing
  private[server] def translateToQuery(expr: Expression, shouldRate: Boolean): String =
    expr match {
      case HistogramExpression(schema, component) => getHisto(schema, component)
      case MetricExpression(schema) => getMetric(schema, shouldRate)
      case ConstantExpression(repr) => repr
      case FunctionExpression(funcName, exprs) =>
        s"$funcName(${exprs.map { expr => translateToQuery(expr, shouldRate) }.mkString(",")})"
      case NoExpression => "null"
    }

  // Form a fully formatted name of the histogram with components
  // the returned metric is styled the same way as admin/metrics.json
  // e.g.request_latency.p9999 or request_latency.min
  private def getHisto(
    metricBuilder: MetricBuilder,
    histoComponent: Either[HistogramComponent, Double]
  ): String = {
    val name = metricBuilder.name.mkString(metadataScopeSeparator())
    val component = histoComponent match {
      case Right(percentile) => statsFormatter.labelPercentile(percentile)
      case Left(Min) => statsFormatter.labelMin
      case Left(Max) => statsFormatter.labelMax
      case Left(Avg) => statsFormatter.labelAverage
      case Left(Sum) => statsFormatter.labelSum
      case Left(Count) => statsFormatter.labelCount
    }
    statsFormatter.histoName(name, component)
  }

  // Form metrics other than histograms, rate() for unlatched counters
  private def getMetric(
    metricBuilder: MetricBuilder,
    shouldRate: Boolean,
  ): String = {
    metricBuilder.metricType match {
      case CounterType if shouldRate =>
        s"rate(${metricBuilder.name.mkString(metadataScopeSeparator())})"
      case other => metricBuilder.name.mkString(metadataScopeSeparator())
    }
  }
}

/**
 * A handler for metric expression queries at admin/metric/expressions.json.
 *
 * @queryParam ?latching_style=boolean Set true to let expression respect the latchedness of counters,
 *             which means it does not wrap the latched counters in `rate()`.
 * @queryParam ?name=string only return expressions whose name matches the value of name
 * @queryParam ?namespace=string only return expressions whose namespace matches the value of namespace.
 *             note that the namespace is a sequence of strings, which will be joined into a string,
 *             separated by :s
 * @example http://$HOST:$PORT/admin/metric/expressions.json?latching_style=true
 *          http://$HOST:$PORT/admin/metric/expressions.json  (by default latching_style is false)
 *          http://$HOST:$PORT/admin/metric/expressions.json?name=success_rate
 *              (grab all expressions named "success_rate")
 *          http://$HOST:$PORT/admin/metric/expressions.json?namespace=path:to:namespace
 *              (grab all expressions of the namespace ["path", "to", "namespace"]
 */
class MetricExpressionHandler(source: MetricSchemaSource = new MetricSchemaSource)
    extends Service[Request, Response] {

  private[this] lazy val sourceLatched = source.hasLatchedCounters

  def apply(request: Request): Future[Response] = {
    val uri = Uri.fromRequest(request)
    val latchParam = uri.params.getAll("latching_style")
    val nameParam = uri.params.getAll("name").toSet
    val namespaceParam = uri.params.getAll("namespace").toSet

    val expressionSchemas = source.expressionList
    val filteredSchemas = expressionSchemas.filter(expressionSchema =>
      // namespace match if namespace param is present
      (namespaceParam.isEmpty || namespaceParam.contains(expressionSchema.namespace.mkString(":")))
      // name match if name param is present
        && (nameParam.isEmpty || nameParam.contains(expressionSchema.name)))

    val shouldRate = latchParam.exists { value =>
      value == "true" || value == "1"
    } && !sourceLatched
    val expressions = filteredSchemas.map { expressionSchema =>
      expressionSchema.copy(exprQuery = translateToQuery(expressionSchema.expr, shouldRate))
    }

    newResponse(
      contentType = MediaType.JsonUtf8,
      content = Buf.Utf8(
        AdminJsonConverter.writeToString(
          Map(
            "@version" -> Version,
            "counters_latched" -> source.hasLatchedCounters,
            "separator_char" -> metadataScopeSeparator(),
            "expressions" -> expressions
          ))
      )
    )
  }

}
