package net.jzajic.graalvm.kadvisor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.jzajic.graalvm.client.DockerClient;
import net.jzajic.graalvm.client.DockerClient.ExecCreateParam;
import net.jzajic.graalvm.client.DockerClient.ExecStartParameter;
import net.jzajic.graalvm.client.LogStream;
import net.jzajic.graalvm.client.exceptions.DockerException;
import net.jzajic.graalvm.client.messages.ContainerInfo;
import net.jzajic.graalvm.client.messages.ExecCreation;
import net.jzajic.graalvm.client.messages.ExecState;
import net.jzajic.graalvm.kadvisor.WatchedContainerRegistry.ContainerListener;

public class ContainerAgentManager implements ContainerListener {

	private final DockerClient dockerClient;
	private final Path agentFolderPath;
	private final Map<String, ExecInfo> execMap = new ConcurrentHashMap<>();
	protected final ExecutorService executorService;
	
	public ContainerAgentManager(DockerClient dockerClient, Path agentFolderPath) {
		super();
		this.dockerClient = dockerClient;
		this.agentFolderPath = agentFolderPath;
		this.executorService = Executors.newCachedThreadPool();
	}
	
	@Override
	public void added(String ipAddress, ContainerInfo info) {
		try {
			dockerClient.copyToContainer(agentFolderPath, info.id, "/opt/node-exporter");
			ExecCreation execCreate = dockerClient.execCreate(info.id, new String[] {"/opt/node-exporter/node_exporter"}, ExecCreateParam.detach());
			ExecInfo execInfo = new ExecInfo();
			execInfo.execCreate = execCreate;
			execInfo.info = info;
			execMap.put(info.id, execInfo);
			LogStream logStream = dockerClient.execStart(execCreate.id, ExecStartParameter.DETACH);			
			executorService.submit(new Callable<Void>() {
		        public Void call() throws Exception {
		        	logStream.attach(System.out, System.err);
		          return null;
		        }
      });
		} catch (DockerException | IOException e) {
			e.printStackTrace();
		}		
	}
	
	@Override
	public void removed(String ipAddress, ContainerInfo info) {
		if(execMap.containsKey(info.id)) {
			execMap.remove(info.id);			
		}		
	}
	
	private static class ExecInfo {
		ContainerInfo info;
		ExecCreation execCreate;
	}
	
	public void stop() {
		execMap.forEach((key, val) -> {
			try {
				ExecState execState = this.dockerClient.execInspect(val.execCreate.id);
				ExecCreation execCreate = dockerClient.execCreate(execState.containerId, new String[] {"kill", "-9", execState.Pid}, ExecCreateParam.attachStderr(), ExecCreateParam.attachStdout());
				dockerClient.execStart(execCreate.id);
			} catch (DockerException e) {
				e.printStackTrace();
			}
		});
	}
	
}
