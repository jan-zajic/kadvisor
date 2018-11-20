package net.jzajic.graalvm.kadvisor;

import java.io.IOException;

public class WindowsIt {
	
	public static void main(String[] args) throws IOException {
		KadvisorLauncher.main(new String[] {"--docker", "npipe:////./pipe/docker_engine"});
	}
	
}
