#!/usr/bin/env bash
source ~/.env_afk
export JAVA_HOME=/home/mstheesha/.local/jdk-17.0.20.1+1
export ANDROID_HOME=/home/mstheesha/Android/Sdk
export ANDROID_NDK_HOME=/home/mstheesha/Android/Sdk/ndk/29.0.14206865
export GOFLAGS=-ldflags=-checklinkname=0
export PATH="$JAVA_HOME/bin:/home/mstheesha/Android/Sdk/platform-tools:$PATH"