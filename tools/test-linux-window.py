#!/usr/bin/env python3
"""Run inside a disposable X11 session with a WM (e.g. Xvfb + Openbox).
Usage: python3 tools/test-linux-window.py path/to/app.AppImage --appimage-extract-and-run
Requires xdotool, wmctrl, xprop and xwininfo. Use a disposable display with no existing app window.
"""
import re
import subprocess
import sys
import time


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def geometry(window):
    text = command('xwininfo', '-id', window)
    return tuple(int(re.search(label + r':\s+(-?\d+)', text).group(1)) for label in
                 ('Absolute upper-left X', 'Absolute upper-left Y', 'Width', 'Height'))



def wait_for(check, message):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.15)
    raise AssertionError(message)


process = subprocess.Popen(sys.argv[1:])
window = None
try:
    def find_window():
        global window
        for line in command('wmctrl', '-lp').splitlines():
            parts = line.split(None, 4)
            if len(parts) == 5 and parts[4] == 'ScreenRecorder':
                window = parts[0]
                return True
        return False

    wait_for(find_window, 'Application window did not appear')
    time.sleep(2)
    assert '_NET_WM_ICON(CARDINAL)' in command('xprop', '-id', window, '_NET_WM_ICON'), 'Missing application icon'
    command('wmctrl', '-ir', window, '-b', 'remove,maximized_vert,maximized_horz')
    command('wmctrl', '-ir', window, '-e', '0,180,160,1000,700')
    time.sleep(1)
    before = geometry(window)
    command('wmctrl', '-ir', window, '-b', 'add,maximized_vert,maximized_horz')
    wait_for(lambda: 'MAXIMIZED_HORZ' in command('xprop', '-id', window, '_NET_WM_STATE'), 'Not maximized')
    time.sleep(.5)
    maximized = geometry(window)
    area = [int(n) for n in re.findall(r'-?\d+', command('xprop', '-root', '_NET_WORKAREA').split('=', 1)[1])][:4]
    assert maximized[2] <= area[2] and maximized[3] <= area[3], (maximized, area)
    command('xdotool', 'windowminimize', window)
    wait_for(lambda: 'HIDDEN' in command('xprop', '-id', window, '_NET_WM_STATE'), 'Not minimized')
    command('wmctrl', '-ia', window)
    wait_for(lambda: 'HIDDEN' not in command('xprop', '-id', window, '_NET_WM_STATE'), 'Not restored from minimized')
    assert 'MAXIMIZED_HORZ' in command('xprop', '-id', window, '_NET_WM_STATE'), 'Lost maximize state'
    command('wmctrl', '-ir', window, '-b', 'remove,maximized_vert,maximized_horz')
    time.sleep(1)
    print(f'Restore expected={before}, actual={geometry(window)}, state={command("xprop", "-id", window, "_NET_WM_STATE")}', flush=True)
    wait_for(lambda: geometry(window) == before, f'Restore bounds did not return to {before}')
    extents = [int(n) for n in re.findall(r'\d+', command('xprop', '-id', window, '_NET_FRAME_EXTENTS').split('=', 1)[1])]
    x, y, width, height = before
    print(f'Before drag={before}, extents={extents}', flush=True)
    # xdotool geometry reports client origin; title bar is above the client.
    command('xdotool', 'mousemove', str(x + 150), str(y - extents[2] // 2), 'mousedown', '1')
    command('xdotool', 'mousemove', str(x + 160), str(y - extents[2] // 2 + 10))
    time.sleep(.2)
    command('xdotool', 'mousemove', str(x + 230), str(y - extents[2] // 2 + 60), 'mouseup', '1')
    print(f'After drag={geometry(window)}', flush=True)
    wait_for(lambda: abs(geometry(window)[0] - x - 80) <= 3 and abs(geometry(window)[1] - y - 60) <= 3, 'Native title drag failed')
    moved = geometry(window)
    x, y, width, height = moved
    command('xdotool', 'mousemove', str(x + width), str(y + 200), 'mousedown', '1')
    command('xdotool', 'mousemove', str(x + width + 10), str(y + 200))
    time.sleep(.2)
    command('xdotool', 'mousemove', str(x + width + 80), str(y + 200), 'mouseup', '1')
    wait_for(lambda: abs(geometry(window)[2] - width - 80) <= 3, 'Native edge resize failed')
    print(f'PASS title/icon; maximize={maximized}; minimize/restore={before}; drag={moved}; resize={geometry(window)}', flush=True)
finally:
    if window:
        subprocess.run(['wmctrl', '-ic', window], check=False)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=10)
