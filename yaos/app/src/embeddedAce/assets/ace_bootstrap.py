"""Supply the embedded engine with the Android context it needs for playback."""

import json
import os
import runpy
import socket
import sys
import threading
import time

import app_bridge


context = json.loads(os.environ["ACE_ANDROID_INFO"])
notifications = {
    "logDebug", "makeToast", "onAuthUpdated", "onEPGUpdated", "onEvent",
    "onPlaylistUpdated", "onSettingsUpdated", "showDialog", "showNotification",
}
control_ports = {int(os.environ["ACE_HTTP_PORT"]), int(os.environ["ACE_API_PORT"])}
original_bind = socket.socket.bind


def bind_locally(sock, address):
    if sock.family in (socket.AF_INET, socket.AF_INET6) and address[1] in control_ports:
        address = (("127.0.0.1" if sock.family == socket.AF_INET else "::1"), address[1])
    return original_bind(sock, address)


def android_call(self, method, *args):
    if method in context:
        return context[method]
    if method in notifications:
        return None
    if method == "getAppVersionCodeById":
        return context["getAppVersionCode"] if args and args[0] == context["getAppId"] else 0
    if method in ("getAvailableBytes", "getFreeBytes", "getTotalBytes", "getAvailableBlocks",
                  "getFreeBlocks", "getBlockCount", "getBlockSize"):
        stats = os.statvfs(context["getAceStreamHome"])
        return {"getAvailableBytes": stats.f_bavail * stats.f_frsize,
                "getFreeBytes": stats.f_bfree * stats.f_frsize,
                "getTotalBytes": stats.f_blocks * stats.f_frsize,
                "getAvailableBlocks": stats.f_bavail, "getFreeBlocks": stats.f_bfree,
                "getBlockCount": stats.f_blocks, "getBlockSize": stats.f_frsize}[method]
    raise AttributeError(method)


def stop_when_parent_exits(parent):
    while os.getppid() == parent:
        time.sleep(2)
    os._exit(0)


threading.Thread(target=stop_when_parent_exits, args=(int(os.environ["ACE_PARENT_PID"]),), daemon=True).start()
socket.socket.bind = bind_locally
app_bridge.Android._fake_rpc = android_call
sys.argv[0] = os.path.join(os.getcwd(), "main.py")
runpy.run_path(sys.argv[0], run_name="__main__")
