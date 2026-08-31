#!/bin/sh
APP_HOME=$(cd "${0%/*}" && pwd -P)
exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
