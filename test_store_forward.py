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

def test_store_and_forward():
    print("==================================================")
    print("TEST 2: STORE-AND-FORWARD RELAY (C disconnects)")
    print("==================================================")
    
    # 1. On C: Go to Home, tap "Stop mesh"
    print("Stopping mesh on Node C...")
    adb(DEVICES["C"], "shell am start -n dev.meshmind.app/.ui.MainActivity")
    time.sleep(1)
    tap_text(DEVICES["C"], "Stop mesh")
    time.sleep(2)
    print("Node C mesh is stopped.")
    
    # 2. On A: Send message to C while C is away
    print("\nOn Node A: Sending store-and-forward message to C-507b...")
    adb(DEVICES["A"], "shell am start -n dev.meshmind.app/.ui.MainActivity")
    time.sleep(1)
    tap_text(DEVICES["A"], "Messages")
    time.sleep(1)
    
    dump_a = get_ui_dump(DEVICES["A"])
    c_matches = find_elements(dump_a, "C-507b")
    if c_matches:
        x, y = c_matches[0]["center"]
        adb(DEVICES["A"], f"shell input tap {x} {y}")
    
    # Change text to "Store and forward test msg"
    msg_field = find_elements(dump_a, "Message")
    if msg_field:
        x, y = msg_field[0]["center"]
        adb(DEVICES["A"], f"shell input tap {x} {y}")
        # Clear field by selecting or sending keyevents
        for _ in range(25):
            adb(DEVICES["A"], "shell input keyevent KEYCODE_DEL")
        adb(DEVICES["A"], "shell input text Store_and_Forward_Msg")
    
    time.sleep(1)
    tap_text(DEVICES["A"], "^Send$")
    print("Message dispatched from A.")
    time.sleep(3)
    
    # 3. Check Node B logcat - verify it buffered the packet
    print("\n--- Node B (Relay) logcat while C is offline ---")
    log_b = adb(DEVICES["B"], "logcat -d -s MeshMind:D")
    for line in log_b.splitlines()[-10:]:
        print("  B:", line)
        
    print("\nWaiting 10s before bringing Node C back online...")
    time.sleep(10)
    
    # 4. Bring Node C back online
    print("Restarting mesh on Node C...")
    tap_text(DEVICES["C"], "Start mesh")
    print("Waiting 15s for reconnection, SUMMARY handshake, and delivery...")
    time.sleep(15)
    
    # 5. Verify delivery on C and ACK on A
    print("\n--- Node C (Destination) logcat after reconnecting ---")
    log_c = adb(DEVICES["C"], "logcat -d -s MeshMind:D")
    for line in log_c.splitlines()[-15:]:
        print("  C:", line)
        
    print("\n--- Node A (Sender) logcat after reconnect ---")
    log_a = adb(DEVICES["A"], "logcat -d -s MeshMind:D")
    for line in log_a.splitlines()[-15:]:
        print("  A:", line)

if __name__ == "__main__":
    test_store_and_forward()
