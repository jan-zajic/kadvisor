package net.jzajic.graalvm.kadvisor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import io.prometheus.client.Collector.MetricFamilySamples;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpHeaders;
import rawhttp.core.RawHttpRequest;
import rawhttp.core.RawHttpResponse;
import rawhttp.core.body.BytesBody;
import rawhttp.core.body.ChunkedBody;
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
    	try(CloseableHttpClient httpclient = HttpClients.createDefault()) {
    		String uri = "http://"+e.ipAddress+":"+e.port+e.path;
    		if (query != null) uri += "?"+query;
    		HttpGet httpGet = new HttpGet(uri);
    		RequestConfig.Builder requestBuilder = RequestConfig.custom();
    		requestBuilder.setSocketTimeout(5000);
        requestBuilder.setConnectTimeout(5000);
        requestBuilder.setConnectionRequestTimeout(60000);        
    		httpGet.setConfig(requestBuilder.build());
    		CloseableHttpResponse singleResponse = httpclient.execute(httpGet);
    		try(InputStream singleStream = singleResponse.getEntity().getContent()) {
    			parser.collect(singleStream, outputSamples, e.tags);
    		}    		
    		singleResponse.close();
    	} catch(IOException ex) {
    		ex.printStackTrace();
    	}
    });
    
    try {
    	writer.write("#KADVISOR\n");
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
