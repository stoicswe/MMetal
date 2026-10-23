#!/usr/bin/env bash
#
# Build MMetal into a ready-to-install mod jar for local use.
#
#   ./scripts/build-local.sh            # incremental build
#   ./scripts/build-local.sh --clean    # clean build (recompiles the Metal shader)
#
# Output: dist/mmetal-<version>.jar
#
# Requirements:
#   - A HotSpot JDK 25+ (Temurin recommended). IBM Semeru / OpenJ9 aborts inside the
#     FFM machinery, so this script refuses to run on OpenJ9. Set JAVA_HOME to override
#     the auto-detection.
#   - macOS with Xcode command line tools for shader compilation (optional: the repo
#     ships a prebuilt MMetalRaytrace.metallib, used when xcrun is unavailable).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CLEAN=0
for arg in "$@"; do
	case "$arg" in
		--clean) CLEAN=1 ;;
		-h|--help)
			sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
			exit 0
			;;
		*)
			echo "Unknown option: $arg (use --clean or --help)" >&2
			exit 2
			;;
	esac
done

# ---------------------------------------------------------------- JDK selection
is_openj9() {
	"$1/bin/java" -version 2>&1 | grep -qi "openj9"
}

is_supported() {
	local java="$1/bin/java"
	[[ -x "$java" ]] || return 1
	local version
	version="$("$java" -version 2>&1 | head -1)"
	# Require version 25 or newer.
	local major
	major="$(echo "$version" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
	[[ -n "$major" && "$major" -ge 25 ]] || return 1
	! is_openj9 "$1"
}

JAVA_HOME_RESOLVED=""
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
	if is_openj9 "$JAVA_HOME"; then
		echo "ERROR: JAVA_HOME points at an OpenJ9 JVM ($JAVA_HOME)." >&2
		echo "       MMetal needs a HotSpot JDK (e.g. Temurin) - see README.md." >&2
		exit 1
	fi
	JAVA_HOME_RESOLVED="$JAVA_HOME"
else
	for candidate in \
		/Library/Java/JavaVirtualMachines/temurin-*/Contents/Home \
		/Library/Java/JavaVirtualMachines/*/Contents/Home \
		"$HOME"/.sdkman/candidates/java/* \
		/usr/lib/jvm/*; do
		[[ -e "$candidate" ]] || continue
		if is_supported "$candidate"; then
			JAVA_HOME_RESOLVED="$candidate"
			break
		fi
	done
fi

if [[ -z "$JAVA_HOME_RESOLVED" ]]; then
	echo "ERROR: no HotSpot JDK 25+ found." >&2
	echo "       Install Temurin 25+ or set JAVA_HOME explicitly." >&2
	exit 1
fi

export JAVA_HOME="$JAVA_HOME_RESOLVED"
export PATH="$JAVA_HOME/bin:$PATH"
echo "JDK: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | sed 's/^/     /'

# ---------------------------------------------------------------- build
ARGS=(build)
if [[ "$CLEAN" -eq 1 ]]; then
	ARGS=(clean build)
fi

echo "Building..."
if ! ./gradlew --console=plain "${ARGS[@]}"; then
	echo "ERROR: gradle build failed." >&2
	exit 1
fi

# ---------------------------------------------------------------- package
mkdir -p dist
JAR="$(ls -t build/libs/*.jar 2>/dev/null | grep -v -e '-sources' -e '-javadoc' -e '-dev' | head -1 || true)"
if [[ -z "$JAR" ]]; then
	echo "ERROR: no jar found in build/libs." >&2
	exit 1
fi

OUT="dist/$(basename "$JAR")"
cp -f "$JAR" "$OUT"

SIZE="$(du -h "$OUT" | cut -f1)"
echo
echo "Built: $OUT ($SIZE)"
echo "Install: drop the jar into .minecraft/mods (Fabric Loader 0.20.1+, Java 25+)."
