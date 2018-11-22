package net.jzajic.graalvm.kadvisor;

public interface KadvisorSh {

	public final String KADVISOR_SH = "'#!/bin/sh'\n" +
	"'PATH=\"/bin:/usr/bin\"'\n" +
	"'if [ -x \"$(command -v pkill)\" ]; then'\n" +
	"'  pkill ${NODE_EXPORTER_NAME}'\n" +
	"'elif [ -x \"$(command -v killall)\" ]; then'\n" +
	"'  killall ${NODE_EXPORTER_NAME}'\n" +
	"'fi'\n" +
	"'exec ${NODE_EXPORTER_PATH} \"$@\"'\n";
	
}
