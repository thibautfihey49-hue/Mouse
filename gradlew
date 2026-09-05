#!/bin/sh
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  PRG=`dirname "$PRG"`"/$link"
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

cygwin=false
darwin=false
case "`uname`" in CYGWIN*) cygwin=true ;; Darwin*) darwin=true ;; esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" -classpath "\"$CLASSPATH\"" \
  org.gradle.wrapper.GradleWrapperMain "$@"
exec "$JAVACMD" "$@"
