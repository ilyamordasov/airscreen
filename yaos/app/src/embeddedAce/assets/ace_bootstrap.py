"""Supply the embedded engine with the Android context it needs for playback."""

import os
import runpy
import socket
import sys
import threading
import time

import app_bridge


context = {
    "getAceStreamHome": os.environ["ACESTREAM_HOME"],
    "getAppDir": os.getcwd(),
    "getAppId": os.environ["ACE_APP_ID"],
    "getAppVersionName": os.environ["ACE_APP_VERSION"],
    "getAppVersionCode": int(os.environ["ACE_APP_CODE"]),
    "getDeviceId": os.environ["ACE_DEVICE_ID"],
    "getDeviceModel": os.environ["ACE_DEVICE_MODEL"],
    "getDeviceManufacturer": os.environ["ACE_DEVICE_MANUFACTURER"],
    "getArch": "arm64-v8a",
    "getDeviceABI": "arm64-v8a",
    "getRAMSize": int(os.environ["ACE_RAM_SIZE"]),
    "getMaxMemory": int(os.environ["ACE_MAX_MEMORY"]),
    "getDisplayLanguage": os.environ["ACE_LANGUAGE"],
}
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
    if method in ("getAvailableBytes", "getFreeBytes", "getTotalBytes"):
        stats = os.statvfs(context["getAceStreamHome"])
        blocks = {"getAvailableBytes": stats.f_bavail, "getFreeBytes": stats.f_bfree,
                  "getTotalBytes": stats.f_blocks}
        return blocks[method] * stats.f_frsize
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
