#!/bin/bash
/bin/kadvisor --docker unix:///var/run/docker.sock --agent /bin/node-exporter --runtime $RUNTIME --label $LABEL --network $NETWORK