import sys
import re
import subprocess
import xml.etree.ElementTree as ET
import time

sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

ADB = r"D:\sonictools\android-sdk\platform-tools\adb.exe"

DEVICES = {
    "A": "ac7626fb",
    "B": "192.168.29.70:41549",
    "C": "192.168.29.146:40875"
}

def adb(device_id, cmd):
    if isinstance(cmd, str):
        full_cmd = [ADB, "-s", device_id] + cmd.split()
    else:
        full_cmd = [ADB, "-s", device_id] + cmd
    res = subprocess.run(full_cmd, capture_output=True, text=True, encoding="utf-8", errors="ignore")
    return res.stdout.strip()

def get_ui_dump(device_id):
    subprocess.run([ADB, "-s", device_id, "shell", "uiautomator", "dump", "/sdcard/dump.xml"], capture_output=True)
    res = subprocess.run([ADB, "-s", device_id, "shell", "cat", "/sdcard/dump.xml"], capture_output=True, text=True, encoding="utf-8", errors="ignore")
    return res.stdout

def find_elements(xml_str, text_pattern):
    try:
        root = ET.fromstring(xml_str)
    except Exception:
        return []
    
    matches = []
    pattern = re.compile(text_pattern, re.IGNORECASE)
    for node in root.iter('node'):
        text = node.attrib.get('text', '')
        desc = node.attrib.get('content-desc', '')
        if pattern.search(text) or pattern.search(desc):
            bounds = node.attrib.get('bounds', '')
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                matches.append({
                    "text": text,
                    "desc": desc,
                    "center": ((x1 + x2) // 2, (y1 + y2) // 2),
                    "bounds": (x1, y1, x2, y2)
                })
    return matches

def tap_text(device_id, text_pattern):
    dump = get_ui_dump(device_id)
    matches = find_elements(dump, text_pattern)
    if matches:
        x, y = matches[0]["center"]
        adb(device_id, f"shell input tap {x} {y}")
        return True
    return False

def test_gateway_live():
    print("==================================================")
    print("TEST 3: LIVE 3-HOP INTERNET GATEWAY REQUEST (A -> B -> C -> Internet)")
    print("==================================================")
    
    # Open Home on A, then Internet via Mesh
    adb(DEVICES["A"], "shell am start -n dev.meshmind.app/.ui.MainActivity")
    time.sleep(1)
    tap_text(DEVICES["A"], "Internet via Mesh")
    time.sleep(1)
    
    # Tap "GET via mesh"
    print("Tapping 'GET via mesh' on Node A...")
    tap_text(DEVICES["A"], "GET via mesh")
    
    print("Waiting 4s for multi-hop gateway fetch and return...")
    time.sleep(4)
    
    # Read logcat on A
    print("\n--- Logcat on Node A (Requester) ---")
    log_a = adb(DEVICES["A"], "logcat -d -s MeshMind:D")
    for line in log_a.splitlines()[-12:]:
        print("  A:", line)
        
    print("\n--- Logcat on Node B (Relay) ---")
    log_b = adb(DEVICES["B"], "logcat -d -s MeshMind:D")
    for line in log_b.splitlines()[-12:]:
        print("  B:", line)

    print("\n--- Logcat on Node C (Gateway) ---")
    log_c = adb(DEVICES["C"], "logcat -d -s MeshMind:D")
    for line in log_c.splitlines()[-12:]:
        print("  C:", line)

if __name__ == "__main__":
    test_gateway_live()
