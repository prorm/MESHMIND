"""Reconnectable read-only logcat capture. Never restarts ADB or changes radios.

Create sessions/SESSION_ID/stop_log_capture to stop cleanly.
"""
import argparse
import json
import subprocess
import threading
import time
import uuid

from benchmark_all import ADB, BASE, now


class Capture:
    def __init__(self, role, alias, directory, events):
        self.role,self.alias,self.directory,self.events=role,alias,directory,events
        self.last=time.monotonic()
        self.process=None
        self.thread=None

    def start(self):
        if self.process is not None:
            self.stop()
        path=self.directory/f"reconnect_{self.role}_{uuid.uuid4().hex[:8]}_logcat.txt"
        self.process=subprocess.Popen([str(ADB),"-s",self.alias,"logcat","-v","epoch","MeshMind:D","AndroidRuntime:E","*:S"],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        self.last=time.monotonic()
        p=self.process
        def copy():
            with path.open("wb") as output:
                for line in iter(p.stdout.readline,b""):
                    output.write(line)
                    output.flush()
                    self.last=time.monotonic()
        self.thread=threading.Thread(target=copy,daemon=True)
        self.thread.start()
        with self.events.open("a",encoding="utf-8") as f:
            f.write(json.dumps(dict(timestamp=now(),role=self.role,alias=self.alias,event="logcat_capture_started",file=path.name))+"\n")

    def stop(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
        if self.thread:
            self.thread.join(timeout=3)


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--session",required=True)
    a=p.parse_args()
    if not __import__('re').fullmatch(r"[A-Za-z0-9_-]+",a.session):
        p.error("Unsafe session ID")
    directory=BASE/"sessions"/a.session
    devices=json.loads((directory/"metadata.json").read_text(encoding="utf-8"))["devices"]
    stop=directory/"stop_log_capture"
    if stop.exists():
        raise RuntimeError("Stop marker already exists")
    captures=[Capture(r,d["adb_id"],directory/"raw",directory/"raw/logcat_reconnections.jsonl") for r,d in devices.items()]
    try:
        for c in captures:
            c.start()
        print("Reconnectable logcat capture active on A/B/C; no radio settings changed",flush=True)
        while not stop.exists():
            for c in captures:
                if c.process.poll() is not None or time.monotonic()-c.last>60:
                    c.start()
                    print(f"Read-only logcat stream restarted for {c.role}",flush=True)
            time.sleep(2)
    finally:
        for c in captures:
            c.stop()
        print("Log capture closed",flush=True)


if __name__=="__main__":
    main()
