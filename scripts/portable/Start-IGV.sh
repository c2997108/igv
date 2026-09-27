#!/bin/sh
# Launch this portable distribution with its bundled Java 21 runtime.
set -eu

# Follow each link relative to its own directory; readlink -f is unavailable on macOS.
igv_script=$0
while [ -L "$igv_script" ]; do
    igv_directory=$(CDPATH= cd -P "$(dirname "$igv_script")" && pwd)
    igv_target=$(readlink "$igv_directory/$(basename "$igv_script")")
    case "$igv_target" in
        /*) igv_script=$igv_target ;;
        *) igv_script=$igv_directory/$igv_target ;;
    esac
done
igv_directory=$(CDPATH= cd -P "$(dirname "$igv_script")" && pwd)
if [ -x "$igv_directory/runtime/Contents/Home/bin/java" ]; then
    JAVA_HOME="$igv_directory/runtime/Contents/Home"
elif [ -x "$igv_directory/runtime/bin/java" ]; then
    JAVA_HOME="$igv_directory/runtime"
else
    echo "Bundled Java was not found. Extract the entire IGV distribution before launching." >&2
    exit 1
fi
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

# Preserve application arguments, including paths containing spaces.
set -- -cp "$igv_directory/lib/*" org.broad.igv.ui.Main "$@"
if [ -f "$HOME/.igv/java_arguments" ]; then
    set -- "@$HOME/.igv/java_arguments" "$@"
fi
if [ "$(uname -s)" = "Darwin" ]; then
    set -- -Xdock:name=IGV "-Xdock:icon=$igv_directory/IGV_64.png" "$@"
fi

# Keep BLAST tools already on PATH ahead of the bundle. IGV resolves the bundled
# blast/<platform>/bin tools itself when no matching tool is available on PATH.
exec "$JAVA_HOME/bin/java" -showversion -Xmx64g \
    "@$igv_directory/igv.args" \
    -Dsamjdk.snappy.disable=true \
    -Dapple.laf.useScreenMenuBar=true \
    -Djava.net.preferIPv4Stack=true \
    -Djava.net.useSystemProxies=true \
    "$@"
