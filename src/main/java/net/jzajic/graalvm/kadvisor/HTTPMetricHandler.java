package net.jzajic.graalvm.kadvisor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.exporter.common.TextFormat;

@SuppressWarnings("restriction")
public class HTTPMetricHandler implements HttpHandler {
	
	private final WatchedContainerRegistry registry;
	private final PrometheusTextFormatParser parser = new PrometheusTextFormatParser();
	
	HTTPMetricHandler(WatchedContainerRegistry registry) {
		super();
		this.registry = registry;
	}
	
	@Override
	public void handle(HttpExchange t) throws IOException {
		String query = t.getRequestURI().getRawQuery();
		
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
    
    PrometheusTextFormatParser.write004(writer, outputSamples.values().iterator());
    response.flush();
    response.close();

    t.getResponseHeaders().set("Content-Type",
    		PrometheusTextFormatParser.CONTENT_TYPE_004);
    if (shouldUseCompression(t)) {
        t.getResponseHeaders().set("Content-Encoding", "gzip");
        t.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
        final GZIPOutputStream os = new GZIPOutputStream(t.getResponseBody());
        response.writeTo(os);
        os.finish();
    } else {
        t.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.size());
        response.writeTo(t.getResponseBody());
    }
    t.close();
	}
	
	protected static boolean shouldUseCompression(HttpExchange exchange) {
    List<String> encodingHeaders = exchange.getRequestHeaders().get("Accept-Encoding");
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
