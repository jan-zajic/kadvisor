package net.jzajic.graalvm.kadvisor;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.google.common.io.CharStreams;
import com.google.common.io.Resources;

import io.prometheus.client.Collector.MetricFamilySamples;

public class PrometheusTextFormatParserTest {
	
	@Test
	public void testParseNodeExporterOutput() throws IOException {
		PrometheusTextFormatParser parser = new PrometheusTextFormatParser();
		List<String> originalLines = Resources.readLines(getClass().getResource("/metrics"), StandardCharsets.UTF_8);
		List<MetricFamilySamples> parsed = parser.parse(getClass().getResourceAsStream("/metrics"));
		StringWriter writer = new StringWriter();
		PrometheusTextFormatParser.write004(writer, parsed.iterator());
		List<String> newLines = CharStreams.readLines(new StringReader(writer.toString()));
		Assert.assertEquals(originalLines.size(), newLines.size());
		int lineNumber = 0;
		for (String originalLine : originalLines) {
			lineNumber++;
			Assert.assertEquals("At line "+lineNumber, originalLine, newLines.get(lineNumber-1));
		}
	}
	
	@Test
	public void testEnhanceLabels() throws IOException {
		PrometheusTextFormatParser parser = new PrometheusTextFormatParser();
		List<String> originalLines = Resources.readLines(getClass().getResource("/metrics"), StandardCharsets.UTF_8);
		StringWriter writer = new StringWriter();
		Map<String, String> labels = new HashMap<>();
		labels.put("node", "test");
		parser.enhance(getClass().getResourceAsStream("/metrics"), writer, labels);
		System.out.println(writer.toString());
		List<String> newLines = CharStreams.readLines(new StringReader(writer.toString()));
		Assert.assertEquals(originalLines.size(), newLines.size());
	}
	
}
