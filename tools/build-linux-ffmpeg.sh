#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=${FFMPEG_VERSION:-8.0.1}
if [[ "$VERSION" == 8.0.1 ]]; then
  FFMPEG_SOURCE_SHA256=${FFMPEG_SOURCE_SHA256:-05ee0b03119b45c0bdb4df654b96802e909e0a752f72e4fe3794f487229e5a41}
fi
CACHE="$ROOT/tools/packaging-cache/linux"
mkdir -p "$CACHE" "$ROOT/ffmpeg/linux/licenses"
# Build dependencies only; end users do not need these packages.
for module in libpulse xcb xcb-shm xcb-xfixes x264 x265 gio-unix-2.0 gtk+-3.0; do
  pkg-config --exists "$module" || { echo "Missing build dependency: $module" >&2; exit 1; }
done
if [[ ! -d "$CACHE/ffmpeg-$VERSION" ]]; then
  curl --fail --location --retry 3 "https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.xz" -o "$CACHE/ffmpeg.tar.xz"
  [[ -z ${FFMPEG_SOURCE_SHA256:-} ]] || echo "$FFMPEG_SOURCE_SHA256  $CACHE/ffmpeg.tar.xz" | sha256sum -c -
  tar -xJf "$CACHE/ffmpeg.tar.xz" -C "$CACHE"
fi
if [[ -f "$CACHE/ffmpeg.tar.xz" ]]; then
  sha256sum "$CACHE/ffmpeg.tar.xz" > "$ROOT/ffmpeg/linux/SOURCE.sha256"
fi
cd "$CACHE/ffmpeg-$VERSION"
./configure --prefix="$ROOT/ffmpeg/linux" --disable-doc --disable-debug --disable-ffplay \
  --enable-gpl --enable-libx264 --enable-libx265 --enable-libpulse \
  --enable-libxcb --enable-libxcb-shm --enable-libxcb-xfixes \
  --disable-shared --enable-static --enable-pic "${@}"
make -j"${JOBS:-$(nproc)}"
# Do not install development archives into the runtime bundle.
install -m755 ffmpeg ffprobe "$ROOT/ffmpeg/linux/"
cp COPYING.GPLv2 "$ROOT/ffmpeg/linux/licenses/FFmpeg-GPLv2.txt"
cp COPYING.GPLv3 "$ROOT/ffmpeg/linux/licenses/FFmpeg-GPLv3.txt"
cp LICENSE.md "$ROOT/ffmpeg/linux/licenses/FFmpeg-LICENSE.md"
printf 'FFmpeg %s\nSource: https://ffmpeg.org/releases/ffmpeg-%s.tar.xz\n' "$VERSION" "$VERSION" > "$ROOT/ffmpeg/linux/BUILD.txt"
./ffmpeg -version >> "$ROOT/ffmpeg/linux/BUILD.txt"
cd "$ROOT"
cc -Wall -Wextra -Werror -O2 native/linux/portal-capture.c -o ffmpeg/linux/portal-capture $(pkg-config --cflags --libs gio-unix-2.0)
cc -Wall -Wextra -Werror -O2 native/linux/x11-windows.c -o ffmpeg/linux/x11-windows -lX11
cc -Wall -Wextra -Werror -O2 native/linux/tray-indicator.c -o ffmpeg/linux/tray-indicator $(pkg-config --cflags --libs gtk+-3.0) -ldl
(cd ffmpeg/linux && sha256sum ffmpeg ffprobe portal-capture x11-windows tray-indicator > checksums.sha256)
