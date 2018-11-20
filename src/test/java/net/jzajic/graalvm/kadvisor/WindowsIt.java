package net.jzajic.graalvm.kadvisor;

import java.io.IOException;

import com.google.common.collect.ImmutableMap;

import net.jzajic.graalvm.client.DefaultDockerClient;
import net.jzajic.graalvm.client.DockerClient;
import net.jzajic.graalvm.client.messages.ContainerConfig;
import net.jzajic.graalvm.client.messages.ContainerCreation;
import net.jzajic.graalvm.client.messages.HostConfig;

public class WindowsIt {
	
	public static void main(String[] args) throws IOException {
		final HostConfig hostConfig = HostConfig.builder().build();
		// Create container
		final ContainerConfig containerConfig = ContainerConfig.builder()
		    .hostConfig(hostConfig)
		    .image("library/busybox")
		    .cmd("sh", "-c", "while :; do sleep 1; done")
		    .labels(ImmutableMap.of("kadvisor", "kadvisor"))
		    .domainname("katest")
		    .hostname("katest")
		    .build;
		String dockerURI = "npipe:////./pipe/docker_engine";
		DockerClient docker = new DefaultDockerClient(dockerURI);
		final ContainerCreation creation = docker.createContainer(containerConfig);
		final String id = creation.id;
		// Start container
		docker.startContainer(id);
		try {
			KadvisorLauncher.main(new String[] {"--docker", dockerURI, "--agent", "./agent/node_exporter"});
		} catch(Exception e) {
			// Kill container
			docker.killContainer(id);
			// Remove container
			docker.removeContainer(id);			
		} finally {
			// Close the docker client
			docker.close();
		}
	}
	
}
