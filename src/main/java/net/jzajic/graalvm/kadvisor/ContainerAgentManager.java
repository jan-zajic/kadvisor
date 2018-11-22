package net.jzajic.graalvm.kadvisor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import net.jzajic.graalvm.client.DockerClient;
import net.jzajic.graalvm.client.DockerClient.ExecCreateParam;
import net.jzajic.graalvm.client.DockerClient.ExecStartParameter;
import net.jzajic.graalvm.client.exceptions.DockerException;
import net.jzajic.graalvm.client.messages.ContainerInfo;
import net.jzajic.graalvm.client.messages.ExecCreation;
import net.jzajic.graalvm.client.messages.ExecState;
import net.jzajic.graalvm.kadvisor.WatchedContainerRegistry.ContainerListener;

public class ContainerAgentManager implements ContainerListener {

	public static final Pattern ARGS_PATTERN = Pattern.compile("([^\\s\"]+|\"[^\"]*\")");
	
	private final DockerClient dockerClient;
	private final Path agentBinaryPath;
	private final String exporterParams;
	private final Map<String, ExecInfo> execMap = new ConcurrentHashMap<>();
	
	public ContainerAgentManager(DockerClient dockerClient, Path agentFolderPath, String exporterParams) {
		super();
		this.dockerClient = dockerClient;
		this.agentBinaryPath = agentFolderPath;
		this.exporterParams = exporterParams;
	}
	
	@Override
	public void added(String ipAddress, ContainerInfo info) {
		try {
			dockerClient.copyToContainer(agentBinaryPath, info.id, "/bin");
			makeExecutable(info.id, "/bin/"+agentBinaryPath.getFileName().toString());
			List<String> cmd = Lists.newArrayList("/bin/"+agentBinaryPath.getFileName().toString());
			if(!Strings.isNullOrEmpty(exporterParams)) {
				Matcher matcher = ARGS_PATTERN.matcher(exporterParams);
				while (matcher.find()) {
					String group = matcher.group(1);
					cmd.add(group);
				}
			}
			ExecCreation execCreate = dockerClient.execCreate(info.id, cmd.toArray(new String[] {}));
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
