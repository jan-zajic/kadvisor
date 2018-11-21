package net.jzajic.graalvm.kadvisor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.jzajic.graalvm.client.DockerClient;
import net.jzajic.graalvm.client.DockerClient.ExecCreateParam;
import net.jzajic.graalvm.client.DockerClient.ExecStartParameter;
import net.jzajic.graalvm.client.exceptions.DockerException;
import net.jzajic.graalvm.client.messages.ContainerInfo;
import net.jzajic.graalvm.client.messages.ExecCreation;
import net.jzajic.graalvm.client.messages.ExecState;
import net.jzajic.graalvm.kadvisor.WatchedContainerRegistry.ContainerListener;

public class ContainerAgentManager implements ContainerListener {

	private final DockerClient dockerClient;
	private final Path agentBinaryPath;
	private final Map<String, ExecInfo> execMap = new ConcurrentHashMap<>();
	
	public ContainerAgentManager(DockerClient dockerClient, Path agentFolderPath) {
		super();
		this.dockerClient = dockerClient;
		this.agentBinaryPath = agentFolderPath;
	}
	
	@Override
	public void added(String ipAddress, ContainerInfo info) {
		try {
			dockerClient.copyToContainer(agentBinaryPath, info.id, "/bin");
			makeExecutable(info.id, "/bin/"+agentBinaryPath.getFileName().toString());
			ExecCreation execCreate = dockerClient.execCreate(info.id, new String[] {
						"/bin/"+agentBinaryPath.getFileName().toString()
					});
			ExecInfo execInfo = new ExecInfo();
			execInfo.execCreate = execCreate;
			execInfo.info = info;
			execMap.put(info.id, execInfo);
			dockerClient.execStart(execCreate.id, ExecStartParameter.DETACH);	
			System.out.println("Started agent /bin/"+agentBinaryPath.getFileName().toString()+" in container "+info.id+" (execution ID "+execCreate.id+")");
		} catch (DockerException | IOException e) {
			e.printStackTrace();
		}		
	}
	
	private void makeExecutable(String id, String path) {
		ExecCreation execCreate = dockerClient.execCreate(id, new String[] {"chmod", "755", path});
		dockerClient.execStart(execCreate.id);
		System.out.println("Changed permission of "+path+" in container "+id);
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
