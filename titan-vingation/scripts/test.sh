#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
bash scripts/build.sh
compiler=(java com.sun.tools.javac.Main)
command -v javac >/dev/null && compiler=(javac)
java_spec=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p')
options=(--release 8)
[[ "$java_spec" == 1.8 ]] && options=(-source 8 -target 8)
mkdir -p build/test-classes
find src/test/java -name '*.java' -print | LC_ALL=C sort > build/test-sources.txt
"${compiler[@]}" "${options[@]}" -encoding UTF-8 -Xlint:all -cp lib/robocode-api.jar:build/classes -d build/test-classes @build/test-sources.txt
java -ea -cp lib/robocode-api.jar:build/classes:build/test-classes titan.tests.RobotRegressionTest
