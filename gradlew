#!/bin/sh
# Gradle wrapper launcher (POSIX sh). Downloads and runs the Gradle version pinned in
# gradle/wrapper/gradle-wrapper.properties.

app_path=$0
while [ -h "$app_path" ]; do
    ls=$(ls -ld "$app_path")
    link=${ls#*' -> '}
    case $link in
        /*) app_path=$link ;;
        *)  app_path=${app_path%/*}/$link ;;
    esac
done
APP_HOME=$(cd -P "$(dirname "$app_path")" > /dev/null && pwd) || exit 1
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
    JAVACMD=$JAVA_HOME/bin/java
    if [ ! -x "$JAVACMD" ]; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    if ! command -v java > /dev/null 2>&1; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command was found in PATH." >&2
        exit 1
    fi
fi

if [ ! -f "$CLASSPATH" ]; then
    echo "ERROR: $CLASSPATH is missing." >&2
    exit 1
fi

exec "$JAVACMD" -Dfile.encoding=UTF-8 -Xmx64m -Xms64m $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=${0##*/}" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
