package net.jzajic.graalvm.kadvisor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import io.prometheus.client.Collector.MetricFamilySamples;
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
