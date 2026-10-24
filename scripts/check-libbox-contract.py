#!/usr/bin/env python3
"""Offline regression checks for assumptions about the pinned libbox 1.14.0 API."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
source = (root / "app/src/main/java/com/adaptivevpn/app/vpn/NativeSingBoxEngine.kt").read_text()
checks = {
    "setup uses SetupOptions": '"io.nekohasekai.libbox.SetupOptions"' in source and 'it.parameterTypes.contentEquals(arrayOf(optionsType))' in source,
    "setup path fields use setters": all(f'setRequiredProperty(options, "{field}"' in source for field in ("basePath", "workingPath", "tempPath")),
    "setup is guarded to run once per process": "if (libboxInitialized) return" in source and "libboxInitialized = true" in source,
    "no obsolete three-string setup signature": 'arrayOf(String::class.java, String::class.java, String::class.java)' not in source,
    "no non-1.14.0 neighbor-resolver callback requirement": '"usePlatformNeighborResolver"' not in source,
    "no non-1.14.0 auto-redirect callback requirement": '"usePlatformAutoRedirect"' not in source and '"createAutoRedirect"' not in source,
    "native config check remains fail-closed": '"checkConfig"' in source and '"تنظیمات توسط خود sing-box رد شد' in source,
    "TUN DNS remains fail-closed": 'فهرست DNS خالی است؛ برای جلوگیری از نشت DNS' in source,
    "HTTPS probe uses strict endpoint checks": 'isExpectedResponse' in (root / "app/src/main/java/com/adaptivevpn/app/vpn/TrafficProbe.kt").read_text(),
    "late probe results cannot overwrite stop": 'generation != probeGeneration' in (root / "app/src/main/java/com/adaptivevpn/app/vpn/AdaptiveVpnService.kt").read_text(),
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
if failed:
    sys.exit(1)
