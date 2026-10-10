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

def get_all_texts(device_id):
    dump = get_ui_dump(device_id)
    try:
        root = ET.fromstring(dump)
    except Exception:
        return []
    texts = []
    for node in root.iter('node'):
        t = node.attrib.get('text', '').strip()
        if t:
            texts.append(t)
    return texts

def get_summary(device_id):
    texts = get_all_texts(device_id)
    res = {}
    for i, t in enumerate(texts):
        if t == "Node ID" and i + 1 < len(texts):
            res["NodeID"] = texts[i+1]
        elif t == "Mesh" and i + 1 < len(texts):
            res["Mesh"] = texts[i+1]
        elif t == "Radio" and i + 1 < len(texts):
            res["Radio"] = texts[i+1]
        elif t == "Connected peers" and i + 1 < len(texts):
            res["Peers"] = texts[i+1]
        elif t == "Internet gateways" and i + 1 < len(texts):
            res["Gateways"] = texts[i+1]
    return res

def start_mesh_all():
    print(">>> Starting mesh on all devices...")
    for name, dev in DEVICES.items():
        tap_text(dev, "Start mesh")
        time.sleep(1)

def poll_status(seconds=20):
    for sec in range(0, seconds, 4):
        time.sleep(4)
        print(f"\n--- Status at {sec+4}s ---")
        for name, dev in DEVICES.items():
            s = get_summary(dev)
            print(f"Device {name}: Mesh={s.get('Mesh')}, Radio={s.get('Radio')}, Peers={s.get('Peers')}")

if __name__ == "__main__":
    start_mesh_all()
    poll_status(25)
