# AdaptiveVPN — 0.15.0 experimental development build

AdaptiveVPN is an Android-first Kotlin project intended to become an adaptive multi-protocol VPN client using sing-box.

## Implemented in this scaffold
- Persian Compose UI and import of sing-box JSON profiles.
- Conservative Android-TUN profile validation and private profile storage.
- Android VPN consent flow and foreground `VpnService` lifecycle.
- Core route-selection policy that admits only approved, compatible, security-approved candidates.
- Experimental reflective `libbox` bridge with fail-closed checks, startup rollback, Android default-network monitoring, real network-interface enumeration, local DNS transport callbacks, and connection-owner lookup.
- Corrected the runtime binding contract to match the pinned `libbox` 1.14.0 API: initialization uses `SetupOptions`, and the preflight no longer requires callbacks introduced only by other API revisions. Unsupported shell/bridge capabilities remain explicitly disabled.
- Profile validation now understands sing-box 1.14+ `address` fields and rejects unsupported per-app/exclude-route/strict-route options rather than silently ignoring them.
- The current TUN adapter requires IPv4 and IPv6 addresses, requires a DNS server from libbox, and ensures both IP families have full-route coverage before establishing TUN. Route coverage is now evaluated in the pure Kotlin core with literal-IP and canonical-prefix validation; required route getters fail closed rather than being silently ignored.
- Post-start HTTPS reachability probe against two independent endpoints. Results are kept separate from a real leak audit; the probe never stores response bodies or public IP details.
- GitHub Actions workflow for Android unit tests and a debug APK build attempt.

## Control-plane logic (0.16.0, core module only)
Pure Kotlin decision logic with unit tests: evidence-based failure classification, bounded recovery (backoff, circuit breaker, hysteresis), a gated protocol registry, and security-gated path scoring. See `CHANGELOG.md` for details. **This logic is not yet connected to the Android service or UI**, so the installed app does not behave differently yet.

## Critical status: not verified for real traffic
This is **not yet a usable or verified VPN**. The `libbox` bridge has not been compiled/run against the actual pinned AAR in this environment. Its platform interface still has unverified/unsupported optional callbacks and config combinations, and no device-level traffic/DNS/leak tests have been run. Service start is reported as `STARTED_UNVERIFIED`; a successful HTTPS probe is reported separately as `TRAFFIC_PROBE_PASSED` and does not prove absence of DNS/IP leaks. Do not use this build for sensitive traffic.

## Important integration guard
The native bridge initializes libbox through `setup(SetupOptions)` and fills only the three required path fields (`BasePath`, `WorkingPath`, `TempPath`). Its preflight follows the pinned 1.14.0 API contract and fails early if the generated Java binding does not expose the expected methods. This avoids inventing positional `setup` parameters, but runtime compatibility still requires an Android build and device test.

## Local verification
```sh
./scripts/verify-local.sh
```

The environment used to prepare this source has Java 21 and Kotlin compiler, but no Gradle or Android SDK. The independent core smoke test passes locally; full Android compilation/APK generation and profile validator JUnit tests were not run here. Research of the upstream 1.14 interface confirms additional callbacks and the DNS iterator change, but source-level alignment is not equivalent to compilation against the actual AAR.

For Android CI, use JDK 17, Android SDK platform 35, Gradle 8.9+, and network access to Google Maven, Maven Central, and JitPack. The GitHub Actions workflow is intended to expose dependency/API compile failures; its success has not been observed from this environment.

## Required before an initial usable release
1. Compile against the exact pinned `libbox` AAR and use the real API signatures.
2. Compile and verify the platform callbacks against the exact generated libbox binding; implement or explicitly reject remaining optional callbacks/config combinations.
3. Verify TUN FD ownership, socket protection, startup rollback, and shutdown ordering. The route-coverage policy has local JVM smoke coverage, but Android-specific route installation still needs device tests.
4. Add DNS-specific health checks and device-level route/leak verification; the current HTTPS probe is only a basic reachability signal.
5. Test IPv4/IPv6, DNS, Wi-Fi/mobile switching, permission revoke, process death, stop/restart and traffic-leak behavior on physical devices.
6. Review GPL-3.0-or-later obligations before distribution.

## Licensing
The configured `com.github.singbox-android:libbox:1.14.0` dependency is intended to use the upstream Android binding. Verify the artifact and license metadata from the actual resolved AAR before distribution. Review and comply with applicable GPL-3.0-or-later obligations.


### libbox compatibility checks (0.14.0)

The Android unit-test suite now inspects the resolved `libbox:1.14.0` AAR using reflection. It verifies the setup/config-check signatures, platform callback surface, TUN option accessors, command handler methods, and CommandServer constructor/factory. These tests are executed by GitHub Actions; they have not been run locally when Gradle/Android SDK are unavailable. Passing these checks does not replace on-device traffic and DNS verification.


### Traffic probe (0.15.0)

After sing-box starts, the service runs two HTTPS reachability checks off the main thread. One expects the Google connectivity endpoint's HTTP 204 response; the other validates that Cloudflare's trace endpoint returns an expected trace marker. Results never include or persist the returned public IP. The UI distinguishes `TRAFFIC_PROBE_PASSED` from a verified VPN state, and a failed probe does not silently stop the engine. A generation token prevents a late probe from overwriting stop/revoke/new-profile status. The response policy has local JVM smoke coverage; actual requests have not been run in this build environment.
