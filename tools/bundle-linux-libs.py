#!/usr/bin/env python3
"""Collect ELF dependencies recursively. Never bundle glibc or GPU drivers."""
import os, pathlib, re, shutil, subprocess, sys
root = pathlib.Path(sys.argv[1]).resolve()
lib = root / 'usr/lib'; lib.mkdir(parents=True, exist_ok=True)
exclude = re.compile(r'^(ld-linux.*|lib(c|m|pthread|dl|rt|resolv|util)\.so.*|lib(GL|EGL|GLX|OpenGL|GLdispatch|nvidia|cuda|drm|gbm).*|libvulkan.*)$')
queue = [p for p in root.rglob('*') if p.is_file() and p.open('rb').read(4) == b'\x7fELF']
seen = set()
while queue:
    binary = queue.pop()
    if binary in seen: continue
    seen.add(binary)
    result = subprocess.run(['ldd', str(binary)], text=True, capture_output=True, env={**os.environ, 'LD_LIBRARY_PATH': ':'.join(str(p) for p in (root / 'usr/runtime/lib', root / 'usr/runtime/lib/server', lib))})
    if 'not found' in result.stdout: raise SystemExit(f'Unresolved dependency for {binary}:\n{result.stdout}')
    for line in result.stdout.splitlines():
        match = re.match(r'\s*(\S+) => (/\S+)', line)
        if not match: continue
        name, source = match.groups()
        if exclude.match(name) or pathlib.Path(source).is_relative_to(root): continue
        dest = lib / name
        if not dest.exists(): shutil.copy2(source, dest); queue.append(dest)
# Preserve installed package copyright/source notices for bundled libraries.
licenses = root / 'usr/share/screenrecorder/licenses'; licenses.mkdir(parents=True, exist_ok=True)
for p in lib.iterdir():
    ownership = subprocess.run(['dpkg-query', '-S', str(pathlib.Path('/usr/lib/x86_64-linux-gnu') / p.name)], text=True, capture_output=True)
    for line in ownership.stdout.splitlines():
        package = line.split(':', 1)[0]
        copyright = pathlib.Path('/usr/share/doc') / package / 'copyright'
        if copyright.is_file(): shutil.copy2(copyright, licenses / (package + '-copyright'))
