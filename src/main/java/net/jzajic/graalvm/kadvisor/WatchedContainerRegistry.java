package net.jzajic.graalvm.kadvisor;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import net.jzajic.graalvm.client.DockerClient;
import net.jzajic.graalvm.client.DockerClient.EventsParam;
import net.jzajic.graalvm.client.DockerClient.ListContainersParam;
import net.jzajic.graalvm.client.EventStream;
import net.jzajic.graalvm.client.messages.AttachedNetwork;
import net.jzajic.graalvm.client.messages.Container;
import net.jzajic.graalvm.client.messages.ContainerInfo;
import net.jzajic.graalvm.client.messages.Event.Type;
import net.jzajic.graalvm.client.messages.NetworkSettings;

public class WatchedContainerRegistry {

	private final Map<String,ContainerInfo> containerMap = new ConcurrentHashMap<>();
	private final Set<ContainerListener> listeners;
	
	private final String runtime;
	private final DockerClient dockerClient;
	private final String networkName;
	
	private volatile boolean running = true;
	
	public WatchedContainerRegistry(DockerClient dockerClient, String label, String runtime, String networkName) {
		this.runtime = runtime;
		this.dockerClient = dockerClient;
		this.networkName = networkName;		
		listeners = Collections.synchronizedSet(Sets.newIdentityHashSet());
		List<Container> containers = dockerClient.listContainers(ListContainersParam.withLabel(label));
		containers.forEach(cont -> {
			inspectContainer(cont.id);
		});
		EventStream events = dockerClient.events(EventsParam.label(label), EventsParam.event("health_status"), EventsParam.event("die"));
		new Thread("Docker event consuming Thread") {
			
			@Override
			public void run() {
				while(running) {
					events.forEachRemaining(action -> {
						if(action.type == Type.CONTAINER) {
							switch(action.action) {
								case "health_status":
									inspectContainer(action.actor.id);
									break;
								case "die":
									removeContainer(action.actor.id);
									break;
							}
						}
					});
				}
			}
			
		};
	}
	
	private void inspectContainer(String containerId) {
		ContainerInfo containerInfo = dockerClient.inspectContainer(containerId);
		if(this.runtime == null || containerInfo.hostConfig.runtime.equals(this.runtime)) {
			NetworkSettings networkSettings = containerInfo.networkSettings;
			AttachedNetwork network;
			if(networkName != null) {
				network = networkSettings.networks.get(networkName);
			} else {
				network = networkSettings.networks.get(0);
			}
			containerMap.put(network.ipAddress, containerInfo);
			notifyAdd(network.ipAddress, containerInfo);
		}
	}

	private void removeContainer(String containerId) {
		ContainerInfo containerInfo = dockerClient.inspectContainer(containerId);
		if(this.runtime == null || containerInfo.hostConfig.runtime.equals(this.runtime)) {
			NetworkSettings networkSettings = containerInfo.networkSettings;
			networkSettings.networks.forEach((key, net) -> {
				ContainerInfo removed = containerMap.remove(net.ipAddress);
				if(removed != null) {
					notifyRemove(net.ipAddress, removed);
				}
			});						
		}
	}
	
	private void notifyAdd(String ipAddress, ContainerInfo containerInfo) {
		synchronized (this.listeners) {
			Iterator<ContainerListener> i = listeners.iterator(); // Must be in the synchronized block
      while (i.hasNext())
          i.next().added(ipAddress, containerInfo);
		}
	}
	
	private void notifyRemove(String ipAddress, ContainerInfo removed) {
		synchronized (this.listeners) {
			Iterator<ContainerListener> i = listeners.iterator(); // Must be in the synchronized block
      while (i.hasNext())
          i.next().removed(ipAddress, removed);
		}
	}

	public void stop() {
		running = false;
	}
	
	public void addListener(ContainerListener listener) {
		this.listeners.add(listener);
	}
	
	public static interface ContainerListener {
		
		public void added(String ipAddress, ContainerInfo info);
		public void removed(String ipAddress, ContainerInfo info);
		
	}
	
	public List<Endpoint> endpoints() {
		return this.containerMap.entrySet().stream().map(entry -> {
			Endpoint e = new Endpoint();
			e.ipAddress = entry.getKey();
			e.port = 9100;
			e.path = "/metrics";
			return e;
		}).collect(Collectors.toList());
	}
	
	public static class Endpoint {
		String ipAddress;
		int port;
		String path;
	}
	
}
