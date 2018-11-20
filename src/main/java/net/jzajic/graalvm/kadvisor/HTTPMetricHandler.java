package net.jzajic.graalvm.kadvisor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import io.prometheus.client.exporter.common.TextFormat;

public class HTTPMetricHandler implements HttpHandler {
	
	private final WatchedContainerRegistry registry;
	
	HTTPMetricHandler(WatchedContainerRegistry registry) {
		super();
		this.registry = registry;
	}

	@Override
	public void handle(HttpExchange t) throws IOException {
		String query = t.getRequestURI().getRawQuery();

    ByteArrayOutputStream response = new ByteArrayOutputStream();
    response.reset();
    //WRITE
    registry.endpoints().forEach(e -> {
    	try(CloseableHttpClient httpclient = HttpClients.createDefault();) {
    		HttpGet httpGet = new HttpGet("http://"+e.ipAddress+e.path+":"+e.port);
    		CloseableHttpResponse singleResponse = httpclient.execute(httpGet);
    		response.write(EntityUtils.toByteArray(singleResponse.getEntity()));
    		response.write('\n');
    		singleResponse.close();
    	} catch(IOException ex) {
    		ex.printStackTrace();
    	}
    });
    response.flush();
    response.close();

    t.getResponseHeaders().set("Content-Type",
            TextFormat.CONTENT_TYPE_004);
    t.getResponseHeaders().set("Content-Length",
            String.valueOf(response.size()));
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
