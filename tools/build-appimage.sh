#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
[[ $(uname -m) == x86_64 ]] || { echo 'This recipe currently targets x86_64 Linux' >&2; exit 1; }
ENGINE="$ROOT/ffmpeg/linux"
for tool in ffmpeg ffprobe portal-capture x11-windows tray-indicator; do
 [[ -x "$ENGINE/$tool" ]] || { echo "Missing project engine $tool; run tools/build-linux-ffmpeg.sh first" >&2; exit 1; }
done
"$ENGINE/ffmpeg" -hide_banner -devices 2>&1 | grep x11grab >/dev/null
"$ENGINE/ffmpeg" -hide_banner -devices 2>&1 | grep pulse >/dev/null
"$ENGINE/ffmpeg" -hide_banner -encoders 2>&1 | grep libx264 >/dev/null
mvn -Plinux,fatjar -DskipTests package
APP="$ROOT/target/ScreenRecorder.AppDir"
# Build in a new directory, never overwrite the user's engine.
[[ ! -e "$APP" ]] || APP="$ROOT/target/ScreenRecorder-$(date +%s).AppDir"
mkdir -p "$APP/usr/share/screenrecorder/ffmpeg" "$APP/usr/bin" "$APP/usr/lib/gstreamer-1.0"
cp target/ScreenRecorder.jar "$APP/usr/share/screenrecorder/"
cp -a "$ENGINE" "$APP/usr/share/screenrecorder/ffmpeg/linux"
JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}
"$JAVA_HOME/bin/jlink" --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.xml,jdk.unsupported,jdk.crypto.ec \
 --strip-debug --no-header-files --no-man-pages --output "$APP/usr/runtime"
cp "$(command -v gst-launch-1.0)" "$(command -v pactl)" "$APP/usr/bin/"
GST_PLUGINS=$(pkg-config --variable=pluginsdir gstreamer-1.0 2>/dev/null || echo /usr/lib/x86_64-linux-gnu/gstreamer-1.0)
for plugin in coreelements pipewire videorate y4menc videotestsrc; do
 cp "$GST_PLUGINS/libgst$plugin.so" "$APP/usr/lib/gstreamer-1.0/"
done
if [[ -f "$GST_PLUGINS/libgstvideoconvertscale.so" ]]; then
 cp "$GST_PLUGINS/libgstvideoconvertscale.so" "$APP/usr/lib/gstreamer-1.0/"
else
 cp "$GST_PLUGINS/libgstvideoconvert.so" "$GST_PLUGINS/libgstvideoscale.so" "$APP/usr/lib/gstreamer-1.0/"
fi
SCANNER=$(find /usr/lib -name gst-plugin-scanner -print -quit)
[[ -n "$SCANNER" ]] || { echo 'Missing GStreamer plugin scanner' >&2; exit 1; }
cp "$SCANNER" "$APP/usr/bin/"
# PipeWire loads its protocol/SPA modules with dlopen; ldd cannot discover these.
cp -a /usr/lib/x86_64-linux-gnu/pipewire-0.3 "$APP/usr/lib/"
cp -a /usr/lib/x86_64-linux-gnu/spa-0.2 "$APP/usr/lib/"
# JavaFX libraries are inside the JAR: extract temporary copies to collect their ELF dependencies.
mkdir -p "$APP/usr/lib/javafx"
(cd "$APP/usr/lib/javafx" && jar xf "$APP/usr/share/screenrecorder/ScreenRecorder.jar" libglass.so libglassgtk3.so libprism_es2.so libprism_sw.so libjavafx_font.so libjavafx_font_freetype.so libjavafx_iio.so)
INDICATOR=$(ldconfig -p | awk '/libayatana-appindicator3.so.1 / {print $NF; exit}')
[[ -n "$INDICATOR" ]] || { echo 'Install libayatana-appindicator3-1 on the build machine' >&2; exit 1; }
cp -L "$INDICATOR" "$APP/usr/lib/libayatana-appindicator3.so.1"
python3 tools/bundle-linux-libs.py "$APP"
cp LICENSE "$APP/usr/share/screenrecorder/"
cp src/main/resources/org/firefly/screenrec/icons/screen-recorder-256.png "$APP/screenrecorder.png"
cp "$APP/screenrecorder.png" "$APP/.DirIcon"
cat > "$APP/screenrecorder.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=ScreenRecorder
Comment=Screen recording with FFmpeg
Exec=AppRun
Icon=screenrecorder
Categories=AudioVideo;Recorder;
Terminal=false
DESKTOP
cat > "$APP/AppRun" <<'RUN'
#!/bin/sh
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export PATH="$HERE/usr/bin:$PATH"
export LD_LIBRARY_PATH="$HERE/usr/lib:$HERE/usr/runtime/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PIPEWIRE_MODULE_DIR="$HERE/usr/lib/pipewire-0.3"
export SPA_PLUGIN_DIR="$HERE/usr/lib/spa-0.2"
export GST_PLUGIN_SYSTEM_PATH_1_0="$HERE/usr/lib/gstreamer-1.0"
export GST_PLUGIN_PATH_1_0="$HERE/usr/lib/gstreamer-1.0"
export GST_PLUGIN_SCANNER_1_0="$HERE/usr/bin/gst-plugin-scanner"
export GST_REGISTRY_1_0="${XDG_CACHE_HOME:-$HOME/.cache}/screenrecorder/gst-registry.bin"
mkdir -p "$(dirname "$GST_REGISTRY_1_0")"
if [ "${1:-}" = --capture-self-test ]; then
 exec gst-launch-1.0 -q videotestsrc num-buffers=1 ! videoconvert ! video/x-raw,format=I420 ! y4menc ! fakesink
fi
if [ "${1:-}" = --engine-version ]; then
 exec "$HERE/usr/share/screenrecorder/ffmpeg/linux/ffmpeg" -version
fi
exec "$HERE/usr/runtime/bin/java" ${SCREENREC_JAVA_OPTS:-} -Dprism.order=sw -jar "$HERE/usr/share/screenrecorder/ScreenRecorder.jar" "$@"
RUN
chmod +x "$APP/AppRun"
TOOL=${APPIMAGETOOL:-$ROOT/tools/packaging-cache/appimagetool-x86_64.AppImage}
[[ -x "$TOOL" ]] || { echo "AppDir prepared: $APP. Set APPIMAGETOOL to a trusted appimagetool executable." >&2; exit 1; }
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" "$APP" "$ROOT/target/ScreenRecorder-x86_64.AppImage"
"$ROOT/target/ScreenRecorder-x86_64.AppImage" --appimage-extract-and-run --engine-version
sha256sum "$ROOT/target/ScreenRecorder-x86_64.AppImage" > "$ROOT/target/ScreenRecorder-x86_64.AppImage.sha256"
