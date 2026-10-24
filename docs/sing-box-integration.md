# sing-box integration status

## Version 0.15.0 — post-start traffic reachability probe

After `CommandServer.startOrReloadService` returns successfully, the Android service runs two bounded HTTPS requests on a background executor. One expects the connectivity-check endpoint to return HTTP 204; the second requires an HTTP 200 trace response with a trace marker. The response body and any public IP value are not logged or stored. Redirects are disabled and TLS hostname/certificate validation remains provided by the platform HTTPS stack.

The status `TRAFFIC_PROBE_PASSED` means only that both HTTPS checks completed while the service session was active. It is **not** a proof of no DNS/IP leaks, correct IPv4/IPv6 routing, or successful reachability of every destination. Probe failures do not stop the engine; they leave it unverified and report bounded diagnostic labels. A generation counter discards late results after stop/revoke or profile changes.

The response policy has local Kotlin smoke and JVM unit tests, but this environment cannot run the Android build or execute network probes.

## Version 0.14.0 — verify the resolved libbox AAR in CI

The JVM unit-test suite inspects the actual resolved `libbox:1.14.0` binding without initializing its native core. It checks setup/config validation signatures, the complete `PlatformInterface` method surface, the complete `TunOptions` accessors, `CommandServerHandler`, and the `CommandServer` factory/constructor. Runtime preflight checks the full `TunOptions` accessor set as well. These tests still need to execute in GitHub Actions.

## Version 0.13.0 — pinned 1.14.0 API alignment

The bridge initializes libbox through `Libbox.setup(SetupOptions)` and sets the required base, working, and temporary paths. Initialization is guarded to run once per process. Preflight does not require callbacks that belong to other API revisions. The changes remove known source-level blockers but do not prove the generated AAR's Java bindings or runtime behavior.

## Version 0.10.0 — Android network and DNS callbacks

The adapter uses Android `ConnectivityManager` callbacks for default-network monitoring, enumerates physical network interfaces, bridges local DNS using `DnsResolver` on Android 10+ and network-bound lookup on Android 8/9, and uses connection-owner lookup on Android 10+ with a native fallback on older supported devices.

## Important limitations — not verified for real traffic

This environment has no Android SDK or Gradle, so Android Kotlin source has not been compiled against the actual pinned AAR. Reflection mappings, TUN file-descriptor ownership, socket protection, DNS cancellation/timeout behavior, interface enumeration, and shutdown ordering must be verified against the exact generated binding and on a device. The HTTPS probe does not replace device-level DNS/IP leak tests. Do not use this source for sensitive traffic until built and device-tested.

## Required before an initial usable release

1. Run `.github/workflows/android-ci.yml` and fix all compile/dependency errors against the pinned AAR.
2. Test TUN FD ownership, socket protection, network-change callbacks, DNS over underlying physical networks, and shutdown ordering.
3. Add DNS-specific health checks and test IPv4/IPv6 routing and traffic leakage on physical devices.
4. Test Wi-Fi/mobile handoff, permission revoke, process death, and stop/restart on physical devices.
5. Review GPL-3.0-or-later obligations before distribution.

## References

- Android VPN service guide: https://developer.android.com/develop/connectivity/vpn
- Android DnsResolver API: https://developer.android.com/reference/android/net/DnsResolver
- Official libbox binding: https://github.com/singbox-android/libbox
- Upstream Android client and reference implementation: https://github.com/SagerNet/sing-box-for-android
