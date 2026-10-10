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

def test_preemption():
    print("==================================================")
    print("TEST 4: SOS PRIORITY PREEMPTION DURING BULK TRANSFER")
    print("==================================================")
    
    # Go to Messages on A
    adb(DEVICES["A"], "shell am start -n dev.meshmind.app/.ui.MainActivity")
    time.sleep(1)
    tap_text(DEVICES["A"], "Messages")
    time.sleep(1)
    
    # Select C-507b
    dump_a = get_ui_dump(DEVICES["A"])
    c_matches = find_elements(dump_a, "C-507b")
    if c_matches:
        x, y = c_matches[0]["center"]
        adb(DEVICES["A"], f"shell input tap {x} {y}")
    
    time.sleep(1)
    print("Starting 512 KB chunked transfer (LOW priority) to C-507b...")
    tap_text(DEVICES["A"], "512 KB")
    
    # Wait 1.5s so transfer is actively streaming chunks across A -> B -> C
    time.sleep(1.5)
    
    # Fire SOS from A
    print("Transfer in flight! Navigating to SOS screen...")
    adb(DEVICES["A"], "shell input keyevent KEYCODE_BACK")
    time.sleep(0.5)
    tap_text(DEVICES["A"], "Send SOS")
    time.sleep(0.8)
    
    print("FIRED MEDICAL SOS (CRITICAL PRIORITY)!")
    tap_text(DEVICES["A"], "MEDICAL SOS")
    
    print("Waiting 10s for transfer and SOS to settle...")
    time.sleep(10)
    
    print("\n--- Logcat on Node A ---")
    log_a = adb(DEVICES["A"], "logcat -d -s MeshMind:D")
    sos_lines = [l for l in log_a.splitlines() if "SOS" in l or "CHUNK" in l or "preempt" in l or "transfer" in l or "rtt=" in l]
    for line in sos_lines[-15:]:
        print("  A:", line)
        
    print("\n--- Logcat on Node B (Relay) ---")
    log_b = adb(DEVICES["B"], "logcat -d -s MeshMind:D")
    sos_b = [l for l in log_b.splitlines() if "SOS" in l or "ACK" in l or "DELIVERY" in l]
    for line in sos_b[-10:]:
        print("  B:", line)

    print("\n--- Logcat on Node C (Destination) ---")
    log_c = adb(DEVICES["C"], "logcat -d -s MeshMind:D")
    sos_c = [l for l in log_c.splitlines() if "SOS" in l or "DELIVERY" in l or "ACK" in l]
    for line in sos_c[-10:]:
        print("  C:", line)

if __name__ == "__main__":
    test_preemption()
