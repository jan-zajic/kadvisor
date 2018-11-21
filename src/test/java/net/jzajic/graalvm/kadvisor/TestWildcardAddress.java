package net.jzajic.graalvm.kadvisor;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.Test;

public class TestWildcardAddress {

	@Test
	public void testListenAll() throws UnknownHostException {
		InetAddress address = Inet4Address.getByName("0.0.0.0");
		System.out.println(address.getHostName());
		System.out.println(address.getHostAddress());
		for (byte b : address.getAddress()) {
			System.out.println(b);
		}
		address = Inet4Address.getByAddress(new byte[] {0, 0, 0, 0});
		System.out.println(address.getHostName());
		System.out.println(address.getHostAddress());
		for (byte b : address.getAddress()) {
			System.out.println(b);
		}
	}
	
}
