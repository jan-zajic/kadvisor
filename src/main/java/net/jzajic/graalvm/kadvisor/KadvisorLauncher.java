package net.jzajic.graalvm.kadvisor;

import java.io.IOException;
import java.nio.file.Paths;

import net.jzajic.graalvm.client.DefaultDockerClient;
import net.jzajic.graalvm.client.DockerClient;
import picocli.CommandLine;
import picocli.CommandLine.AbstractParseResultHandler;
import picocli.CommandLine.DefaultExceptionHandler;
import picocli.CommandLine.ExecutionException;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

public class KadvisorLauncher extends AbstractParseResultHandler<Integer> {
	
	private static final CommandSpec spec = CommandSpec.create()
			.name("kadvisor")
			.version("kadvisor 0.1")
			.mixinStandardHelpOptions(true)
			.addOption(OptionSpec.builder("--docker")
	        .paramLabel("docker")
	        .type(String.class)
	        .defaultValue("unix:///var/run/docker.sock")
	        .description("Docker uri to connect to.").build())
			.addOption(OptionSpec.builder("--runtime")
	        .paramLabel("runtime")
	        .type(String.class)
	        .description("Docker runtime name used to launch watched containers.").build())
			.addOption(OptionSpec.builder("--label")
	        .paramLabel("label")
	        .defaultValue("kadvisor")
	        .type(String.class)
	        .description("Label used to identify watched containers.").build())
			.addOption(OptionSpec.builder("--network")
	        .paramLabel("network")
	        .type(String.class)
	        .description("Network in which containers export metrics.").build())
			.addOption(OptionSpec.builder("--port")
	        .paramLabel("port")
	        .type(Integer.class)
	        .defaultValue("1234")
	        .description("Port to export prometheus metrics.").build())
			.addOption(OptionSpec.builder("--agent")
	        .paramLabel("agent")
	        .type(String.class)
	        .description("Path to agent binary file (node-exporter).").build())
			.addOption(OptionSpec.builder("--ipv6")
	        .paramLabel("ipv6")
	        .type(Boolean.class)
	        .description("Enable ipv6.").build())
			;
	
	private static final CommandLine commandLine = new CommandLine(spec);
	
	private DockerClient dockerClient;
	private WatchedContainerRegistry registry;
	private ContainerAgentManager manager;
	
	private int port;
	private String label;
	private String runtime;
	private String network;
	private String dockerURI;
	private String agent;
	
	public static void main(String[] args) throws IOException {
		KadvisorLauncher instance = new KadvisorLauncher();
		Integer parseResult = commandLine.parseWithHandlers(
				instance.useOut(System.out),
        new DefaultExceptionHandler<Integer>().andExit(567),
        args);
		if(parseResult != null && parseResult == 0) {
			instance.start();
		}
	}
	
	private void start() throws IOException {				
		dockerClient = new DefaultDockerClient(dockerURI);
		registry = new WatchedContainerRegistry(dockerClient, label, runtime, network);
		manager = new ContainerAgentManager(dockerClient, Paths.get(this.agent));
		registry.addListener(manager);
		final HTTPServer server = new HTTPServer(registry, port);
		
		Runtime.getRuntime().addShutdownHook(new Thread()
    {
        @Override
        public void run()
        {
        		System.out.println("INTERRUPTED, EXITING");
          	server.stop();
          	registry.stop();
        }
    });
	}
	
	@Override
	protected Integer handle(ParseResult parseResult) throws ExecutionException {
		Boolean enableIpv6 = parseResult.matchedOptionValue("ipv6", false);
		if(!enableIpv6) {
			System.setProperty("java.net.preferIPv4Stack", "true");
		}
		this.port = parseResult.matchedOptionValue("port", 1234);
		this.dockerURI = parseResult.matchedOptionValue("docker", "unix:///var/run/docker.sock");
		this.label = parseResult.matchedOptionValue("label", "kadvisor");
		this.runtime = parseResult.matchedOptionValue("runtime", null);
		this.network = parseResult.matchedOptionValue("network", null);
		this.agent = parseResult.matchedOptionValue("agent", null);
		if(this.agent == null) {
			throw new ExecutionException(commandLine, "Agent required");
		}
		return 0;
	}

	@Override
	protected AbstractParseResultHandler<Integer> self() {
		return this;
	}
	
}
