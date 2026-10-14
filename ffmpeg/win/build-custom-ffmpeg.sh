#!/usr/bin/env bash
set -euo pipefail
ROOT="/d/Files/Project/Java/ScreenRecorder"
CACHE="$ROOT/tools/packaging-cache"
DEPS="$CACHE/custom-deps/mingw64"
export PATH="$DEPS/bin:$CACHE/custom-deps/usr/bin:/mingw64/bin:/usr/bin:/d/Program Files/NASM:$PATH"
export PKG_CONFIG_LIBDIR="$DEPS/lib/pkgconfig"
export PKG_CONFIG_PATH="$DEPS/lib/pkgconfig"
# Packages use /mingw64 prefixes; redirect them to the isolated dependency tree.
export PKG_CONFIG_SYSROOT_DIR="$CACHE/custom-deps"
cd "$CACHE/custom-source/ffmpeg-9.0.2"
# Idempotent local cursor lifecycle patch (kept with distribution sources).
if ! grep -q 'Borrow the shared cursor' libavdevice/gdigrab.c; then
 patch -p1 < "$ROOT/tools/gdigrab-cursor.patch"
fi
if ! grep -q 'int        desktop_coords;' libavfilter/vsrc_ddagrab.c; then
 patch -p1 < "$ROOT/tools/ddagrab-desktop.patch"
fi
./configure --prefix="$CACHE/custom-engine" --target-os=mingw32 --arch=x86_64 \
 --disable-autodetect --disable-debug --disable-doc --disable-ffplay --disable-network \
 --enable-gpl --enable-shared --disable-static --enable-libx264 --enable-libx265 --enable-libvpl --enable-zlib \
 --enable-ffnvcodec --enable-nvenc --enable-amf --enable-d3d11va --enable-dxva2 \
 --disable-encoders --enable-encoder=libx264,libx265,h264_nvenc,hevc_nvenc,h264_amf,hevc_amf,h264_qsv,hevc_qsv,aac,pcm_s16le,rawvideo,gif,mjpeg,png \
 --disable-muxers --enable-muxer=mp4,mov,matroska,ipod,gif,image2,rawvideo,pcm_s16le,null \
 --disable-devices --enable-indev=gdigrab,dshow,lavfi \
 --disable-filters --enable-filter=ddagrab,hwdownload,format,scale,fps,pad,crop,volume,amix,aresample,aformat,anull,null,split,palettegen,paletteuse,testsrc,testsrc2,sine,color,anullsrc,atrim,trim,setpts,asetpts \
 --disable-protocols --enable-protocol=file,pipe \
 --extra-cflags="-I$DEPS/include" --extra-ldflags="-L$DEPS/lib"
make -j6
make install
for name in libx264-165.dll libx265-217.dll libvpl-2.dll zlib1.dll; do cp "$DEPS/bin/$name" "$CACHE/custom-engine/bin/"; done
for name in libstdc++-6.dll libgcc_s_seh-1.dll libwinpthread-1.dll; do cp "/mingw64/bin/$name" "$CACHE/custom-engine/bin/"; done
cp COPYING.GPLv3 "$CACHE/custom-engine/LICENSE.txt"
