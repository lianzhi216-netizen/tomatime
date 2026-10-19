"""Drives the installed app on a CI emulator with adb and saves a screenshot after each step.

Usage: python3 ci/ui_test.py <apk> <out_dir>
Nothing here asserts; the screenshots, steps.txt and crash.txt are reviewed afterwards.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
LOG = open(os.path.join(OUT, "steps.txt"), "w", encoding="utf-8")
COUNT = [0]


def adb(*args):
    return subprocess.run(["adb", *map(str, args)], capture_output=True).stdout


def note(msg):
    print(msg, flush=True)
    LOG.write(msg + "\n")
    LOG.flush()


def shot(name):
    COUNT[0] += 1
    time.sleep(1.5)
    png = adb("exec-out", "screencap", "-p")
    with open(os.path.join(OUT, f"{COUNT[0]:02d}-{name}.png"), "wb") as f:
        f.write(png)
    note(f"shot {COUNT[0]:02d}-{name}")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/d.xml")
    raw = adb("exec-out", "cat", "/sdcard/d.xml").decode("utf-8", "ignore")
    try:
        root = ET.fromstring(raw)
    except Exception as e:  # noqa: BLE001
        note(f"dump parse failed: {e}")
        return []
    out = []
    for el in root.iter("node"):
        b = re.findall(r"\d+", el.get("bounds", ""))
        if len(b) == 4:
            out.append((el.get("text") or "", el.get("content-desc") or "", tuple(map(int, b))))
    return out


def find(text, exact=True, pick="first"):
    for _ in range(6):
        hits = [b for t, d, b in nodes() if (t == text if exact else text in t)]
        if hits:
            if pick == "bottom":
                return max(hits, key=lambda b: b[1])
            return hits[0]
        time.sleep(1)
    return None


def center(b):
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def tap_xy(x, y, label=""):
    adb("shell", "input", "tap", x, y)
    note(f"tap {label} at {x},{y}")
    time.sleep(1.2)


def tap(text, exact=True, pick="first"):
    b = find(text, exact, pick)
    if not b:
        note(f"NOT FOUND: {text}")
        shot("missing")
        return False
    x, y = center(b)
    tap_xy(x, y, text)
    return True


def back():
    adb("shell", "input", "keyevent", 4)
    note("back")
    time.sleep(1.2)


def long_drag(x, y1, y2):
    """Press, hold still past the long-press timeout, then move: the same gesture a finger makes."""
    adb("shell", "input", "motionevent", "DOWN", x, y1)
    time.sleep(1.0)
    for k in range(1, 13):
        adb("shell", "input", "motionevent", "MOVE", x, int(y1 + (y2 - y1) * k / 12))
        time.sleep(0.05)
    adb("shell", "input", "motionevent", "UP", x, y2)
    note(f"long drag x={x} {y1}->{y2}")
    time.sleep(1.5)


def scroll_find(text, exact=True, tries=8):
    for _ in range(tries):
        b = find(text, exact)
        if b:
            return b
        adb("shell", "input", "swipe", 540, 1900, 540, 900, 300)
        time.sleep(0.8)
    return None


def hour_y(h):
    b = find(f"{h:02d}")
    return b[1] if b else None


def crash_text():
    c = adb("logcat", "-d", "-b", "crash").decode("utf-8", "ignore")
    return c if "tomatime" in c else ""


note(adb("install", "-r", APK).decode("utf-8", "ignore").strip())
adb("shell", "pm", "grant", "com.tomatime.app", "android.permission.POST_NOTIFICATIONS")
adb("shell", "appops", "set", "com.tomatime.app", "SYSTEM_ALERT_WINDOW", "allow")
adb("logcat", "-c")
adb("shell", "am", "start", "-n", "com.tomatime.app/.MainActivity")
time.sleep(5)
shot("onboarding")
tap("3. 開始使用")
time.sleep(2)
shot("week")

now_h = int(adb("shell", "date", "+%H").decode().strip() or "12")
month, day = adb("shell", "date", "+%m/%d").decode().strip().split("/")
md = f"{int(month)}/{int(day)}"
note(f"device time hour={now_h} date={md}")
head = find(md)
col_x = center(head)[0] if head else None
note(f"today column x={col_x}")

# 幫我排: homework, default settings, then confirm the first suggestion.
tap("📝", pick="bottom")
shot("choose-how")
tap("幫我排")
shot("plan-form")
tap("找時間")
time.sleep(2)
shot("suggest")
tap("確認")
shot("after-auto-plan")

# 自己排 in the past (so it can get feedback) and 補登 of leisure.
if col_x is not None:
    past_a, past_b = now_h - 4, now_h - 3
    if past_a >= 7 and hour_y(past_a) and hour_y(past_b):
        tap("📝", pick="bottom")
        tap("自己排")
        shot("select-mode")
        long_drag(col_x, hour_y(past_a) + 10, hour_y(past_b) + 10)
        shot("confirm-plan")
        tap("儲存")
        shot("after-self-plan")
        # Open it and report half done.
        tap_xy(col_x, (hour_y(past_a) + hour_y(past_b)) // 2, "planned block")
        shot("detail-past")
        if tap("🟡 做一半"):
            shot("remaining")
            tap("確定")
            shot("after-partial")
    else:
        note("skip self-plan: too early in the day")

    log_a, log_b = now_h - 2, now_h - 1
    if log_a >= 7 and hour_y(log_a) and hour_y(log_b):
        tap("🎮", pick="bottom")
        shot("log-mode")
        long_drag(col_x, hour_y(log_a) + 10, hour_y(log_b) + 10)
        shot("after-log")
        tap_xy(col_x, (hour_y(log_a) + hour_y(log_b)) // 2, "logged block")
        shot("detail-logged")
        back()
    else:
        note("skip log: too early in the day")

    # Single-day view.
    tap(md)
    shot("day-view")
    back()

# Week swipe and back.
b = find("☰")
if b:
    y_mid = 1200
    adb("shell", "input", "swipe", 900, y_mid, 150, y_mid, 250)
    note("swipe to next week")
    shot("next-week")
    tap("回到本週")

tap("☰")
shot("menu")
tap("未完成清單", exact=False)
shot("unfinished")
back()

# Stats and overview.
tap("☰")
tap("統計")
shot("stats")
back()
tap("10月", exact=False)
shot("overview")
back()

# Pomodoro.
tap("🍅")
shot("pomo-start")
if not tap("開始專注", exact=False):
    tap("📚讀書")
shot("pomo-running")
tap("💭 想到別的事")
shot("pomo-thought")
back()
tap("結束任務")
shot("pomo-ended")

# Settings, then the sleep guard test with the medium overlay.
tap("☰")
tap("設定")
shot("settings")
b = scroll_find("睡覺時間滑手機時提醒我")
if b:
    tap_xy(1000, center(b)[1], "guard switch")
    shot("guard-settings")
    t = scroll_find("測試 5 分鐘", exact=False)
    if t:
        tap_xy(*center(t), "guard test")
        adb("shell", "input", "keyevent", 3)
        note("home")
        time.sleep(14)
        shot("guard-medium")
        # Overlay windows are invisible to uiautomator, so the button is tapped by position.
        tap_xy(540, 1476, "我知道了 (medium)")
        shot("guard-medium-closed")
        time.sleep(8)
        shot("guard-medium-again")
        note("waiting 65 s without touching to check the 60 s auto-close")
        time.sleep(65)
        shot("guard-after-autoclose")
        # Switch to the strong mode.
        adb("shell", "am", "start", "-n", "com.tomatime.app/.MainActivity")
        time.sleep(2)
        tap_xy(540, 1476, "close overlay if any")
        st = scroll_find("強：要等 5 秒", exact=False)
        if st:
            tap_xy(*center(st), "strong")
            adb("shell", "input", "keyevent", 3)
            time.sleep(8)
            shot("guard-strong")
            tap_xy(540, 1476, "我知道了 too early (should do nothing)")
            shot("guard-strong-early")
            time.sleep(5)
            shot("guard-strong-ready")
            tap_xy(540, 1476, "我知道了 (strong)")
            shot("guard-strong-closed")
            # Pull down the notification shade: it must stay usable above the overlay.
            time.sleep(4)
            adb("shell", "cmd", "statusbar", "expand-notifications")
            time.sleep(2)
            shot("guard-shade")
            adb("shell", "cmd", "statusbar", "collapse")
adb("shell", "am", "start", "-n", "com.tomatime.app/.MainActivity")
time.sleep(2)
shot("back-in-app")
tap_xy(540, 1476, "close overlay if any")
stop = scroll_find("運作中", exact=False)
if stop:
    tap_xy(*center(stop), "stop guard")
    shot("guard-stopped")
pid = adb("shell", "pidof", "com.tomatime.app").decode().strip()
note(f"app process alive: {bool(pid)}")
crash = crash_text()
with open(os.path.join(OUT, "crash.txt"), "w", encoding="utf-8") as f:
    f.write(crash or "no crash")
note("CRASH FOUND" if crash else "no crash")
