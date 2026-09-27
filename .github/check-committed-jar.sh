#!/usr/bin/env bash
# Checks that the plugin jar committed in the repository root matches the project: exactly one
# cerebellar-layer-plugin-*.jar, named and built for the version in pom.xml. Also compares it with
# the jar just built in target/ and warns (without failing) if the contents differ, which means
# the source changed without the committed jar being rebuilt.
#
# Run after `./mvnw verify`. Emits GitHub Actions annotations.
set -euo pipefail

version=$(./mvnw -B -q -ntp help:evaluate -Dexpression=project.version -DforceStdout)
jar="cerebellar-layer-plugin-${version}.jar"
rebuild="Run ./mvnw verify and copy target/${jar} to the repository root, replacing the old jar."

shopt -s nullglob
committed=(cerebellar-layer-plugin-*.jar)
if [ ! -f "$jar" ]; then
    echo "::error::${jar} is missing from the repository root. ${rebuild}"
    exit 1
fi
if [ "${#committed[@]}" -ne 1 ]; then
    echo "::error::Keep exactly one plugin jar in the repository root (found: ${committed[*]})."
    exit 1
fi

stamped=$(unzip -p "$jar" org/cerebellum/morphometry/build.properties | tr -d '\r')
if [ "$stamped" != "version=${version}" ]; then
    echo "::error file=${jar}::${jar} was not built from version ${version} (it says '${stamped}'). ${rebuild}"
    exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -q "$jar" -d "$tmp/committed"
unzip -q "target/$jar" -d "$tmp/built"
if ! diff -rq "$tmp/committed" "$tmp/built" > "$tmp/diff.txt"; then
    echo "::warning file=${jar}::The committed ${jar} differs from a fresh build of this commit. If the source changed, rebuild it: ${rebuild}"
    cat "$tmp/diff.txt"
else
    echo "${jar} matches a fresh build of this commit."
fi
