#!/bin/sh
# usage: ./run-scenario.sh <scenario> [extra gradle args...]; prints CHECK lines from the client log
mode=$1; shift
export JAVA_HOME="C:/Users/henk/.jdks/jdk-25.0.5+8"
timeout 560 ./gradlew runClient -Pdevtest=$mode --console=plain "$@" > run-$mode.log 2>&1
echo "exit $?"
grep -E "CHECK|Exception|scrobbling failed|skipped minecraft" run/logs/latest.log | grep -vE "Realms|authlib|^\s+at |HTTP_ERROR" | cut -c1-230
