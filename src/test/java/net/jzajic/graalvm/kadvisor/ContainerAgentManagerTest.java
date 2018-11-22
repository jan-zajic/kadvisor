package net.jzajic.graalvm.kadvisor;

import java.util.List;
import java.util.regex.Matcher;

import org.junit.Assert;
import org.junit.Test;

import com.google.common.collect.Lists;


public class ContainerAgentManagerTest {
	
	@Test
	public void testArgParserSimple() {
		testArgParser("--no-collector.timex", 1);
	}
	
	@Test
	public void testArgParserSimpleQuoted() {
		testArgParser("'--no-collector.timex'", 1);
	}
	
	@Test
	public void testArgParserTwo() {
		testArgParser("--no-collector.timex --no-collector.any", 2);
	}
	
	@Test
	public void testArgParserQuotedWhitespace() {
		testArgParser("--no-collector.timex \"aa bb\"", 2);
	}
	
	private void testArgParser(String argStr, int expectedCount) {
		List<String> cmd = Lists.newArrayList();
		Matcher matcher = ContainerAgentManager.ARGS_PATTERN.matcher(argStr);
		while (matcher.find()) {
			String group = matcher.group(1);
			cmd.add(group);
			System.out.println(group);
		}
		Assert.assertEquals(expectedCount, cmd.size());
	}
	
}
