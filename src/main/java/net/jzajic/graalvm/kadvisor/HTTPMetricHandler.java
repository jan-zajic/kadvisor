package net.jzajic.graalvm.kadvisor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import com.google.common.collect.Multimap;

import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.Collector.Type;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpHeaders;
import rawhttp.core.RawHttpRequest;
import rawhttp.core.RawHttpResponse;
import rawhttp.core.body.BodyReader;
import rawhttp.core.body.BytesBody;
import rawhttp.core.body.StringBody;

public class HTTPMetricHandler {
	
	private final WatchedContainerRegistry registry;
	private final PrometheusTextFormatParser parser = new PrometheusTextFormatParser();
	
	RawHttp http = new RawHttp();
	
	HTTPMetricHandler(WatchedContainerRegistry registry) {
		super();
		this.registry = registry;
	}
	
	public Optional<RawHttpResponse<?>> handle(RawHttpRequest req) {
		String query = req.getUri().getRawQuery();
		
    ByteArrayOutputStream response = new ByteArrayOutputStream();
    Writer writer = new OutputStreamWriter(response);
    response.reset();    
    //WRITE
    final Map<String,MetricFamilySamples> outputSamples = new HashMap<>();
    registry.endpoints().forEach(e -> {
    	try(Socket socket = new Socket(e.ipAddress, e.port); OutputStream socketOs = socket.getOutputStream();) {
    		String getURI = e.path;
    		if (query != null) getURI += "?"+query;
    		RawHttpRequest request = http.parseRequest(
    		    "GET "+getURI+" HTTP/1.1\r\n" +
    		    "User-Agent: kadvisor/0.1\r\n" +
    		    "Accept-Encoding: identity\r\n" +
    		    "Host: "+e.ipAddress+"\r\n");
    		request.writeTo(socketOs);
    		socketOs.flush();
    		RawHttpResponse<?> rawResponse = http.parseResponse(socket.getInputStream()).eagerly();
    		Optional<? extends BodyReader> body = rawResponse.getBody();
  			parser.collect(body.get().asRawStream(), outputSamples, e.tags);
    	} catch(IOException ex) {
    		ex.printStackTrace();
    	}
    });
    
    computeMetrics(outputSamples);
    
    try {
    	writer.write("# KADVISOR\n");
	    PrometheusTextFormatParser.write004(writer, outputSamples.values().iterator());
	    writer.flush();
	    response.close();
	    
	    RawHttpResponse<Void> resp = http.parseResponse("HTTP/1.0 200 OK\n" +
	        "Content-Type: "+PrometheusTextFormatParser.CONTENT_TYPE_004+"\n");
	    if (shouldUseCompression(req)) {
	        ByteArrayOutputStream bos = new ByteArrayOutputStream();
	        final GZIPOutputStream os = new GZIPOutputStream(bos);
	        response.writeTo(os);
	        os.finish();
	        os.close();
	        return Optional.of(resp.
	        		withHeaders(RawHttpHeaders.newBuilder().with("Content-Encoding", "gzip").build())
	        		.withBody(new BytesBody(bos.toByteArray())));
	    } else {
	    		return Optional.of(resp.withBody(new BytesBody(response.toByteArray())));
	    }
    } catch(IOException e) {
    	e.printStackTrace();
    	return Optional.of(http.parseResponse("HTTP/1.0 500 Internal Server Error\n" +
          "Content-Type: text/plain").withBody(new StringBody("Internal Server Error")));
    }
	}
	
	private void computeMetrics(Map<String, MetricFamilySamples> outputSamples) {
		MetricFamilySamples totalMemBytes = outputSamples.get("node_memory_MemTotal_bytes");
		MetricFamilySamples freeBytes = outputSamples.get("node_memory_MemFree_bytes");
		MetricFamilySamples cachedBytes = outputSamples.get("node_memory_Cached_bytes");
		MetricFamilySamples buffersBytes = outputSamples.get("node_memory_Buffers_bytes");
		
		if(totalMemBytes != null && freeBytes != null && cachedBytes != null && buffersBytes != null) {
			GroupedMetric totalMemPerLabels = groupMetrics(totalMemBytes);
			GroupedMetric freePerLabels = groupMetrics(freeBytes);
			GroupedMetric cachedPerLabels = groupMetrics(cachedBytes);
			GroupedMetric buffersPerLabels = groupMetrics(buffersBytes);
			GroupedMetric memUsageBytes = totalMemPerLabels.minus(freePerLabels, "container_memory_usage_bytes");
			GroupedMetric memRssBytes =  memUsageBytes.minus(buffersPerLabels, "xxx").minus(cachedPerLabels, "container_memory_rss");
			addMetric(outputSamples, memUsageBytes, totalMemBytes.type, "Current memory usage in bytes, including all memory regardless of when it was accessed");
			addMetric(outputSamples, memRssBytes, totalMemBytes.type, "Size of RSS in bytes.");
		}
	}
	
	private void addMetric(Map<String, MetricFamilySamples> outputSamples, GroupedMetric memUsageBytes, Type type, String help) {
		List<Sample> samples = new ArrayList<>(memUsageBytes.groupedValues.size());
		memUsageBytes.groupedValues.forEach((key, val) -> {
			List<String> labelNames = new ArrayList<>(key.labels.size());
			List<String> labelValues = new ArrayList<>(key.labels.size());
			key.labels.forEach((labelKey, labelVal) -> {
				labelNames.add(labelKey);
				labelValues.add(labelVal);
			});
			Sample sample = new Sample(key.name, labelNames, labelValues, val);
			samples.add(sample);
		});
		outputSamples.put(memUsageBytes.name, new MetricFamilySamples(memUsageBytes.name, type, help, samples));
	}

	private GroupedMetric groupMetrics(MetricFamilySamples samples) {
		Map<SampleKey, Double> grouped = new HashMap<>();
		samples.samples.forEach(sample -> {
			grouped.compute(new SampleKey(sample), (key, currentValue) -> {
				if(currentValue == null)
					return sample.value;
				else
					return currentValue+sample.value;
			});
		});
		return new GroupedMetric(samples.name, grouped);
	}
	
	private static class GroupedMetric {
		public final String name;
		public Map<SampleKey, Double> groupedValues;
		
		private GroupedMetric(String name, Map<SampleKey, Double> groupedValues) {
			super();
			this.name = name;
			this.groupedValues = groupedValues;
		}
		
		public GroupedMetric minus(GroupedMetric other, String newName) {
			Map<SampleKey, Double> grouped = new HashMap<>();
			groupedValues.forEach((key,val) -> {
				if(other.groupedValues.containsKey(key)) {
					grouped.put(key, val-other.groupedValues.get(key));
				} else {
					grouped.put(key, val);
				}
			});
			return new GroupedMetric(newName, grouped);
		}
		
		public GroupedMetric plus(GroupedMetric other, String newName) {
			Map<SampleKey, Double> grouped = new HashMap<>();
			groupedValues.forEach((key,val) -> {
				if(other.groupedValues.containsKey(key)) {
					grouped.put(key, val+other.groupedValues.get(key));
				} else {
					grouped.put(key, val);
				}
			});
			return new GroupedMetric(newName, grouped);
		}
		
	}

	private static class SampleKey {
		public final String name;
    public final Map<String,String> labels;
    
    public SampleKey(Sample sample) {
    	this.name = sample.name;
      this.labels = new HashMap<>();
      for(int i = 0; i < sample.labelNames.size(); i++) {
      	this.labels.put(sample.labelNames.get(i), sample.labelValues.get(i));
      }
    }
    
    public SampleKey(String name, Map<String,String> labels) {
      this.name = name;
      this.labels = labels;
    }
    
    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof SampleKey)) {
        return false;
      }
      SampleKey other = (SampleKey) obj;

      return other.name.equals(name) && other.labels.equals(labels);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 37 * hash + name.hashCode();
      hash = 37 * hash + labels.hashCode();
      return hash;
    }

    @Override
    public String toString() {
      return "Name: " + name + " Labels: " + labels;
    }
    
	}
	
	protected static boolean shouldUseCompression(RawHttpRequest req) {		
    List<String> encodingHeaders = req.getHeaders().get("Accept-Encoding");
    if (encodingHeaders == null) return false;

    for (String encodingHeader : encodingHeaders) {
        String[] encodings = encodingHeader.split(",");
        for (String encoding : encodings) {
            if (encoding.trim().toLowerCase().equals("gzip")) {
                return true;
            }
        }
    }
    return false;
}

}
