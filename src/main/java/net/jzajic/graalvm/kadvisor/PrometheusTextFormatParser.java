
package net.jzajic.graalvm.kadvisor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Splitter;

import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.Collector.Type;

/**
 * Parses the Prometheus Text format.
 * <p>
 * See
 * https://prometheus.io/docs/instrumenting/exposition_formats/#text-format-details
 */
public class PrometheusTextFormatParser {

	public static final Pattern PATTERN = Pattern.compile("([^\\s{}]+|\\{[^{}]*\\})");

	/**
	 * Content-type for text version 0.0.4.
	 */
	public final static String CONTENT_TYPE_004 = "text/plain; version=0.0.4; charset=utf-8";

	/**
	 * UTF-8 charset. Used for decoding the given input stream.
	 */
	private static final Charset UTF_8 = Charset.forName("utf-8");

	public List<MetricFamilySamples> parse(InputStream stream) {
		List<MetricFamilySamples> resultList = new ArrayList<>();
		parse(stream, resultList::add, null);
		return resultList;
	}
	
	public void collect(InputStream singleStream, Map<String,MetricFamilySamples> output, Map<String, String> tags) {
		parse(singleStream, samples -> {		
			if(output.containsKey(samples.name)) {
				MetricFamilySamples existingSamples = output.get(samples.name);
				samples.samples.forEach(sample -> {
					if(tags != null && !tags.isEmpty()) {
						tags.forEach((key, value) -> {
							sample.labelNames.add(key);
							sample.labelValues.add(value);
						});
					}
					existingSamples.samples.add(sample);
				});
			} else {
				if(tags != null && !tags.isEmpty()) {
					samples.samples.forEach(sample -> {
						tags.forEach((key, value) -> {
							sample.labelNames.add(key);
							sample.labelValues.add(value);
						});
					});
				}
				output.put(samples.name, samples);
			}
		}, null);
	}
	
	public void enhance(InputStream singleStream, Writer response, Map<String, String> tags) {
		parse(singleStream, null, new Callback() {

			@Override
			public void commentLine(String line) throws IOException {
				response.append(line).append('\n');
			}

			@Override
			public void sample(Sample sample) throws IOException {
				response.append(sample.name);
				if (tags != null) {
					tags.forEach((key, value) -> {
						sample.labelNames.add(key);
						sample.labelValues.add(value);
					});
				}
				writeSample(response, sample);
			}

			@Override
			public void typeLine(String line, TypeLine typeLine) throws IOException {
				response.append(line).append('\n');
			}

			@Override
			public void helpLine(String line, String help) throws IOException {
				response.append(line).append('\n');
			}

			@Override
			public void emptyLine() throws IOException {
				response.write('\n');
			}
		});
	}

	public void parse(InputStream stream, Consumer<MetricFamilySamples> collector, Callback callback) {	
		BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF_8));
		String line;
		int lineNumber = 0;
		try {
			TypeLine typeLine = null;
			String help = null;
			List<Sample> samples = null;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isEmpty()) {
					if (callback != null)
						callback.emptyLine();
					continue;
				}
				if (isHelpLine(line)) {
					addLastType(typeLine, help, samples, collector);
					typeLine = null;
					help = parseHelpLine(line);
					if (callback != null)
						callback.helpLine(line, help);
					continue;
				}
				if (isTypeLine(line)) {
					addLastType(typeLine, help, samples, collector);
					typeLine = parseTypeLine(line);
					samples = new ArrayList<>();
					if (callback != null)
						callback.typeLine(line, typeLine);
					continue;
				}
				if (isCommentLine(line)) {
					if (callback != null)
						callback.commentLine(line);
					continue;
				}

				// Example: http_requests_total{method="post",code="200"} 1027 1395066363000
				Matcher matcher = PATTERN.matcher(line);
				List<String> parts = new ArrayList<>(2);
				String tagsPart = null;
				while (matcher.find()) {
					String group = matcher.group(1);
					if (group.startsWith("{"))
						tagsPart = group;
					else
						parts.add(group);
				}
				// At least 2 parts, because timestamp is optional
				if (parts.size() < 2) {
					throw new RuntimeException("Expected at least 2 parts, found " + parts.size() + " at line " + lineNumber);
				}

				String metricName = parts.get(0);
				Instant timestamp = getMetricTimestamp(parts);
				double value = getMetricValue(parts);
				Map<String, String> tags = getMetricTags(tagsPart);
				Sample point = addPoint(metricName, timestamp, value, tags);
				samples.add(point);
				if (callback != null)
					callback.sample(point);
			}
			addLastType(typeLine, help, samples, collector);
		} catch (IOException e) {
			throw new RuntimeException("Error at line " + lineNumber, e);
		} catch (RuntimeException e) {
			throw new RuntimeException("Error at line " + lineNumber, e);
		}

	}

	private void addLastType(TypeLine typeLine, String help, List<Sample> samples, Consumer<MetricFamilySamples> collector) {
		if (typeLine != null && samples != null && collector != null) {
			collector.accept(new MetricFamilySamples(typeLine.metricName, Type.valueOf(typeLine.type.toUpperCase()), help, samples));
		}
	}

	/**
	 * Adds a point to the given metrics map. If the metric doesn't exist in the
	 * map, it will be created.
	 *
	 * @param metrics
	 *          Metric map.
	 * @param metricName
	 *          Name of the metric.
	 * @param timestamp
	 *          Timestamp of the point.
	 * @param value
	 *          Value of the point.
	 * @param tags
	 *          Tags for the metric. These are only used if the metric doesn't
	 *          already exist in the metrics map.
	 */
	private Sample addPoint(String metricName, Instant timestamp, double value, Map<String, String> tags) {
		List<String> labelNames = new ArrayList<>(tags.size());
		List<String> labelValues = new ArrayList<>(tags.size());
		for (Map.Entry<String, String> tagEntry : tags.entrySet()) {
			labelNames.add(tagEntry.getKey());
			labelValues.add(tagEntry.getValue());
		}
		return new Sample(metricName, labelNames, labelValues, value, timestamp != null ? timestamp.toEpochMilli() : null);
	}

	/**
	 * Extract the metric tags from the parts.
	 *
	 * @param parts
	 *          Parts.
	 * @return Metric tags.
	 */
	private Map<String, String> getMetricTags(String tagString) {
		if (tagString == null || tagString.isEmpty()) {
			return new HashMap<>();
		}
		int startSquare = tagString.indexOf('{');
		int stopSquare = tagString.indexOf('}');
		List<String> tags = Splitter.on(',').splitToList(tagString.substring(startSquare + 1, stopSquare));

		Map<String, String> result = new HashMap<>();

		for (String tag : tags) {
			List<String> tagParts = Splitter.on('=').limit(2).splitToList(tag);
			if (tagParts.size() != 2) {
				throw new RuntimeException("Expected 2 tag parts, found " + tagParts.size() + " in tag '" + tag + "'");
			}
			String tagValue = tagParts.get(1);
			if (!tagValue.startsWith("\"") && !tagValue.endsWith("\"")) {
				throw new RuntimeException("Expected the tag value between \"s, but it isn't. Tag: '" + tag + "'");
			}

			String tagWithoutQuotes = tagValue.substring(1, tagValue.length() - 1);
			result.put(tagParts.get(0), tagWithoutQuotes);
		}

		return result;
	}

	/**
	 * Extracts the metric timestamp from the parts.
	 *
	 * @param parts
	 *          Parts.
	 * @return Metric timestamp.
	 * 
	 */
	private Instant getMetricTimestamp(List<String> parts) {
		// If the timestamp is missing, wall clock time is assumed.
		if (parts.size() < 3) {
			return null;
		}

		String value = parts.get(2);
		try {
			long epochTime = Long.parseLong(value);
			return Instant.ofEpochMilli(epochTime);
		} catch (NumberFormatException e) {
			throw new RuntimeException("Can't convert '" + value + "' to long", e);
		}
	}

	/**
	 * Extracts the metric value from the given parts.
	 *
	 * @param parts
	 *          Parts.
	 * @return Metric value.
	 * 
	 */
	private double getMetricValue(List<String> parts) {
		String value = parts.get(1);
		return getMetricValue(value);
	}

	static double getMetricValue(String value) {
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			throw new RuntimeException("Can't convert '" + value + "' to double", e);
		}
	}

	/**
	 * Parses a type line.
	 *
	 * @param line
	 *          Line to parse.
	 * @return Parsed type line.
	 */
	private TypeLine parseTypeLine(String line) {
		// Example: TYPE http_requests_total counter
		List<String> parts = Splitter.on(' ').splitToList(line);
		if (parts.size() != 4) {
			throw new RuntimeException("Expected 4 parts in TYPE line, found " + parts.size() + " in line '" + line + "'");
		}

		// First two parts are '#' and 'TYPE'
		return new TypeLine(parts.get(2), parts.get(3));
	}

	/**
	 * Parses a help line.
	 *
	 * @param line
	 *          Line to parse.
	 * @return Parsed type line.
	 */
	private String parseHelpLine(String line) {
		// Example: HELP http_requests_total some help text
		List<String> parts = Splitter.on(' ').limit(4).splitToList(line);
		// First three parts are '#' and 'HELP' and 'metric_name'
		return parts.get(3);
	}

	private boolean isCommentLine(String line) {
		return line.startsWith("#");
	}

	private boolean isTypeLine(String line) {
		return line.startsWith("# TYPE");
	}

	private boolean isHelpLine(String line) {
		return line.startsWith("# HELP");
	}

	/**
	 * DTO for a type line.
	 */
	private static class TypeLine {
		private final String metricName;
		private final String type;

		public TypeLine(String metricName, String type) {
			this.metricName = metricName;
			this.type = type;
		}

		public String getMetricName() {
			return metricName;
		}

		public String getType() {
			return type;
		}
	}

	private static interface Callback {
		void commentLine(String line) throws IOException;

		void sample(Sample point) throws IOException;

		void typeLine(String line, TypeLine typeLine) throws IOException;

		void helpLine(String line, String help) throws IOException;

		void emptyLine() throws IOException;
	}

	/**
	 * Write out the text version 0.0.4 of the given MetricFamilySamples.
	 */
	public static void write004(Writer writer, Iterator<Collector.MetricFamilySamples> mfs) throws IOException {
		/*
		 * See http://prometheus.io/docs/instrumenting/exposition_formats/ for the
		 * output format specification.
		 */
		while (mfs.hasNext()) {
			Collector.MetricFamilySamples metricFamilySamples = mfs.next();
			writer.write("# HELP ");
			writer.write(metricFamilySamples.name);
			writer.write(' ');
			writeEscapedHelp(writer, metricFamilySamples.help);
			writer.write('\n');

			writer.write("# TYPE ");
			writer.write(metricFamilySamples.name);
			writer.write(' ');
			writer.write(typeString(metricFamilySamples.type));
			writer.write('\n');

			writeSamples(writer, metricFamilySamples);
		}
	}

	private static void writeSamples(Writer writer, Collector.MetricFamilySamples metricFamilySamples) throws IOException {
		for (Collector.MetricFamilySamples.Sample sample : metricFamilySamples.samples) {
			writeSample(writer, sample);
		}
	}

	private static void writeSample(Writer writer, Collector.MetricFamilySamples.Sample sample) throws IOException {
		writer.write(sample.name);
		int labelCount = sample.labelNames.size();
		if (labelCount > 0) {
			writer.write('{');
			for (int i = 0; i < labelCount; ++i) {
				writer.write(sample.labelNames.get(i));
				writer.write("=\"");
				writeEscapedLabelValue(writer, sample.labelValues.get(i));
				writer.write("\"");
				if (i < (labelCount - 1))
					writer.write(",");
			}
			writer.write('}');
		}
		writer.write(' ');
		writer.write(doubleToGoString(sample.value));
		if (sample.timestampMs != null) {
			writer.write(' ');
			writer.write(sample.timestampMs.toString());
		}
		writer.write('\n');
	}
	
	/**
	 * Convert a double to its string representation in Go, simulates fo format
	 * from prometheus sources - https://github.com/prometheus/common/blob/master/expfmt/text_create.go
	 */
	public static String doubleToGoString(double d) {
		if (d == Double.POSITIVE_INFINITY) {
			return "+Inf";
		}
		if (d == Double.NEGATIVE_INFINITY) {
			return "-Inf";
		}
		if (Double.isNaN(d)) {
			return "NaN";
		}
		if(d == 1) {
			return "1";
		}
		if(d == 0) {
			return "0";
		}
		if (d == -1) {
			return "-1";
		}
		return Double.toString(d);
	}

	private static void writeEscapedHelp(Writer writer, String s) throws IOException {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '\\':
				writer.append("\\\\");
				break;
			case '\n':
				writer.append("\\n");
				break;
			default:
				writer.append(c);
			}
		}
	}

	private static void writeEscapedLabelValue(Writer writer, String s) throws IOException {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '\\':
				writer.append("\\\\");
				break;
			case '\"':
				writer.append("\\\"");
				break;
			case '\n':
				writer.append("\\n");
				break;
			default:
				writer.append(c);
			}
		}
	}

	private static void writeEscapedLabelValue(OutputStream writer, String s) throws IOException {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '\\':
				writer.write("\\\\".getBytes());
				break;
			case '\"':
				writer.write("\\\"".getBytes());
				break;
			case '\n':
				writer.write("\\n".getBytes());
				break;
			default:
				writer.write(c);
			}
		}
	}

	private static String typeString(Collector.Type t) {
		switch (t) {
		case GAUGE:
			return "gauge";
		case COUNTER:
			return "counter";
		case SUMMARY:
			return "summary";
		case HISTOGRAM:
			return "histogram";
		default:
			return "untyped";
		}
	}

}