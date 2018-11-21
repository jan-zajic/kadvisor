package net.jzajic.graalvm.kadvisor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

@SuppressWarnings("restriction")
public class HTTPServer {

	protected final HttpServer server;
  protected final ExecutorService executorService;
	
  /**
   * Start a HTTP server serving Prometheus metrics from the given registry.
   */
  public HTTPServer(WatchedContainerRegistry registry, InetSocketAddress addr, boolean daemon) throws IOException {
      server = HttpServer.create();
      server.bind(addr, 3);
      HttpHandler mHandler = new HTTPMetricHandler(registry);
      server.createContext("/", mHandler);
      server.createContext("/metrics", mHandler);
      executorService = Executors.newFixedThreadPool(5, DaemonThreadFactory.defaultThreadFactory(daemon));
      server.setExecutor(executorService);
      start(daemon);
  }
  
  /**
   * Start a HTTP server serving the default Prometheus registry.
   */
  public HTTPServer(WatchedContainerRegistry registry, int port) throws IOException {
      this(registry, new InetSocketAddress(port), false);
  }

	/**
   * Start a HTTP server by making sure that its background thread inherit proper daemon flag.
   */
  public void start(boolean daemon) {
      if (daemon == Thread.currentThread().isDaemon()) {
          server.start();
      } else {
          FutureTask<Void> startTask = new FutureTask<Void>(new Runnable() {
              @Override
              public void run() {
                  server.start();
              }
          }, null);
          DaemonThreadFactory.defaultThreadFactory(daemon).newThread(startTask).start();
          try {
              startTask.get();
          } catch (ExecutionException e) {
              throw new RuntimeException("Unexpected exception on starting HTTPSever", e);
          } catch (InterruptedException e) {
              // This is possible only if the current tread has been interrupted,
              // but in real use cases this should not happen.
              // In any case, there is nothing to do, except to propagate interrupted flag.
              Thread.currentThread().interrupt();
          }
      }
  }

  /**
   * Stop the HTTP server.
   */
  public void stop() {
      server.stop(0);
      executorService.shutdown(); // Free any (parked/idle) threads in pool
  }
  
  static class DaemonThreadFactory implements ThreadFactory {
    private ThreadFactory delegate;
    private final boolean daemon;

    DaemonThreadFactory(ThreadFactory delegate, boolean daemon) {
        this.delegate = delegate;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = delegate.newThread(r);
        t.setDaemon(daemon);
        return t;
    }

    static ThreadFactory defaultThreadFactory(boolean daemon) {
        return new DaemonThreadFactory(Executors.defaultThreadFactory(), daemon);
    }
  }
	
}
