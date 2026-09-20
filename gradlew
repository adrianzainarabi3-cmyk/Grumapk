#!/bin/sh
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then JAVACMD="$JAVA_HOME/jre/sh/java"
    else JAVACMD="$JAVA_HOME/bin/java" ; fi
else JAVACMD="java" ; fi
APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
