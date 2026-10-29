#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
command -v java >/dev/null || { echo 'Instale um JDK 8 ou superior.' >&2; exit 1; }
compiler=(java com.sun.tools.javac.Main)
command -v javac >/dev/null && compiler=(javac)
jar_tool=(java sun.tools.jar.Main)
command -v jar >/dev/null && jar_tool=(jar)
java_spec=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p')
options=(--release 8)
[[ "$java_spec" == 1.8 ]] && options=(-source 8 -target 8)
rm -rf build/classes
mkdir -p build/classes dist
find src/main/java -name '*.java' -print | LC_ALL=C sort > build/sources.txt
"${compiler[@]}" "${options[@]}" -encoding UTF-8 -Xlint:all -cp lib/robocode-api.jar -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
"${jar_tool[@]}" cf dist/titan-vingation.jar -C build/classes .
echo 'Compilação concluída: dist/titan-vingation.jar (Java 8)'
