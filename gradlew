#!/bin/sh
#
# Gradle startup script for Unix
#

APP_HOME=`dirname "$0"`
APP_HOME=`cd "$APP_HOME" && pwd`
APP_NAME="gradlew"
CLASSPATH=""
PRG="$0"

while [ "$PRG" != "/" ] ; do
    if [ ! -x "$PRG" ] ; then
        CLASSPATH="$PRG:$CLASSPATH"
    fi
    PRG=`dirname "$PRG"`
done
APP_HOME=`dirname "$PRG"`

JAVACMD="java"
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/bin/java" ] ; then
        JAVACMD="$JAVA_HOME/bin/java"
    fi
fi

if [ -z "$JAVACMD" ] ; then
    JAVACMD="java"
fi

exec "$JAVACMD" -Xmx$JVM_OPTS -cp "$CLASSPATH" org.gradle.launcher.GradleMain "$@"