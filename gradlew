#!/usr/bin/env sh
# Lightweight Gradle bootstrap wrapper. Keeps the project buildable without a local Gradle install.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION="8.13"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-${HOME}/.gradle}"
DIST_ROOT="${GRADLE_USER_HOME}/wrapper/dists/gradle-${GRADLE_VERSION}-bin/animego"
GRADLE_BIN="${DIST_ROOT}/gradle-${GRADLE_VERSION}/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$DIST_ROOT"
  ZIP_FILE="${DIST_ROOT}/gradle-${GRADLE_VERSION}-bin.zip"
  echo "Downloading Gradle ${GRADLE_VERSION}..."
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --output "$ZIP_FILE" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_FILE" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  else
    echo "curl or wget is required to download Gradle." >&2
    exit 1
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to extract Gradle." >&2; exit 1; }
  unzip -q -o "$ZIP_FILE" -d "$DIST_ROOT"
  rm -f "$ZIP_FILE"
fi
exec "$GRADLE_BIN" -p "$APP_HOME" "$@"
