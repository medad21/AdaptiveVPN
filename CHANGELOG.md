# Changelog

## 0.16.0 — control-plane decision logic (core only, not yet wired into the app)
- Added `FailureClassifier`: maps independent evidence to the 13 failure kinds. A result is `confirmed` only when the evidence directly shows the cause (OS state, engine-reported errors, repeated probes with an independent control group); otherwise it is a probable cause. It distinguishes "target is down" from "the whole network is down" and never claims DPI/censorship was detected.
- Added `RecoveryCoordinator` with `BackoffPolicy` (exponential, jittered, capped), per-route `CircuitBreaker` (half-open single trial), and switching hysteresis (minimum dwell time, minimum score gain, switch-rate limit). Every recovery episode is bounded by an attempt limit and a time limit; there is no infinite retry loop. Network loss waits for an OS event instead of polling. Failures that are not the route's fault do not penalize it, and confirmed non-retryable failures (authentication, configuration, unsupported transport) block only that route.
- Added `ProtocolRegistry` with an enforced status graph. Only `APPROVED` entries are operational, and approval requires a reviewed license, an approved security posture, and a passing test for the current version. A new engine version resets security approval and removes the entry from operation until it is re-reviewed. A failed security review disables an approved entry immediately. Every change is written to an audit log.
- Added `PathScorer`/`PathScoreBoard` with configurable weights, per-network-class results, and confidence/freshness shrinkage toward a neutral prior so one lucky or stale result cannot establish permanent superiority. Security is a gate, not a score component: a route that is not security-approved gets operational score 0 regardless of speed.
- 58 new unit tests (70 in total in `core`). They run in CI via the new `gradle :core:test` step (this is the first time the `core` tests run under Gradle) and locally via `scripts/run-core-tests-local.sh`, which uses a small local `kotlin.test` shim because JUnit is not available offline. A mutation check (deliberately breaking the attempt limit, the security gate, the approval gate, and confirmed-ness) was caught by 6 tests.
- Not done yet: none of this is connected to the Android service, UI, persistence (Room), or `WorkManager`. The classifier needs real probe adapters (direct server probes, independent control probes, DNS probes) that run outside the tunnel. Android compilation of this release, device behavior and traffic tests remain unverified.

## 0.13.0 — align bridge with the pinned 1.14.0 API
- Fixed a blocking integration mismatch: `Libbox.setup` is invoked with a `SetupOptions` object, not three positional strings. The bridge sets the required base, working, and temporary paths through the binding setters.
- Removed preflight requirements for `UsePlatformNeighborResolver`, `UsePlatformAutoRedirect`, and `CreateAutoRedirect`, which are not part of the pinned sing-box/libbox 1.14.0 `PlatformInterface` contract. This previously guaranteed startup rejection before TUN creation.
- Added an offline source-contract audit to catch regressions in these API assumptions.
- Confirmed the pinned 1.14.0 API shape against the upstream versioned package documentation; Android compilation against the resolved AAR and device traffic tests remain unverified.

## 0.12.0 — libbox 1.14 callback contract hardening
- Expanded runtime preflight to check the `PlatformInterface` callbacks introduced or used by sing-box/libbox 1.14, including neighbor monitoring, shell, bridge, auto-redirect, notifications, and network callbacks.
- Added preflight checks for all `CommandServerHandler` methods expected by the 1.14 API.
- Improved `setup` mismatch errors by listing the actual available `setup` method signatures from the installed binding.
- Corrected the optional `checkPlatformShell` callback behavior: the capability probe returns successfully while `usePlatformShell` remains false.
- Disabled unsupported system-proxy toggling explicitly instead of returning success without applying a change.
- Confirmed against upstream 1.14 documentation that `TunOptions.dnsServerAddress` is an iterator; the current bridge already consumes it as an iterator and retains the non-empty DNS fail-closed check.
- Android compile against the resolved AAR, device connectivity, DNS, and leak tests remain unverified in this environment.

## 0.10.0 — Android platform callback increment
- Added a real Android `ConnectivityManager` default-network callback bridge for sing-box interface-change notifications; callbacks are unregistered on engine shutdown and failed startup.
- Implemented network-interface enumeration from Android `Network`, `LinkProperties`, `NetworkCapabilities`, and Java network-interface data instead of returning placeholder data.
- Added a local DNS transport bridge using Android `DnsResolver` on Android 10+ and a network-bound resolver on Android 8/9. DNS operations have an eight-second timeout and cancel on timeout.
- Added Android connection-owner lookup using `ConnectivityManager.getConnectionOwnerUid` on Android 10+, with the native fallback on older supported versions.
- Updated the profile gate for sing-box 1.14+ combined `address` arrays and rejected `dns_mode=disabled`, strict-route, unsupported per-app selectors, and route-exclude options.
- TUN setup now requires both address families and a non-empty DNS server list, adds full-family routes when configured ranges do not cover the whole address space, and preserves the underlying network's metered state.
- Added an early binding preflight for the required `PlatformInterface` and `TunOptions` methods.
- Kept the engine status `STARTED_UNVERIFIED`; no connected state is claimed without an independent traffic/DNS health check.
- Updated local verification and documentation to clearly distinguish core smoke tests from Android build/device tests.

## 0.9.0 — integration hardening
- ProfileStore enforces the same profile validator as the UI.
- Native engine startup rolls back CommandServer and TUN resources on failure.
- Unknown libbox setup parameters fail closed.
- Added profile validation tests for multiple TUN inbounds, UID-based TUN options, and oversized profiles.


## 0.11.0 — Fail-closed route coverage
- Extracted full-tunnel route coverage into the pure Kotlin core so it can be exercised without Android SDK.
- Validate IPv4/IPv6 route family, literal IP format, prefix range, and canonical network address before installing routes. Hostnames are rejected without DNS resolution.
- Removed exception swallowing around required libbox route getters; missing/broken route metadata now aborts TUN creation instead of being silently omitted.
- Added smoke/unit cases for default routes, dual /1 coverage, partial routes, invalid hostnames, non-canonical prefixes, family mismatch, and out-of-range prefixes.
- Android build, exact libbox AAR compatibility, and device traffic tests remain unverified.

## 0.14.0 — verify the resolved libbox AAR in CI
- Added JVM reflection contract tests that load the actual resolved `libbox:1.14.0` binding without initializing its native core.
- The tests check `Libbox.setup(SetupOptions)`, `checkConfig(String)`, all 1.14.0 `PlatformInterface` callbacks, the full `TunOptions` surface, `CommandServerHandler`, and the `CommandServer` factory/constructor signature.
- This closes an important testing gap: Kotlin compilation alone cannot validate reflective method names. The tests run in the existing GitHub Actions `testDebugUnitTest` job before APK assembly.
- Local source checks and core smoke tests are available here; these new AAR-backed tests still require the GitHub Actions/Gradle environment to execute.


## 0.15.0 — post-start HTTPS reachability probe
- Added a bounded HTTPS probe to two independent endpoints after sing-box startup, running off the Android main thread.
- Added a distinct `TRAFFIC_PROBE_PASSED` state; a successful probe is not described as proof that DNS/IP traffic cannot leak. Failed probes leave the engine running but keep it unverified.
- The probe does not persist or log endpoint response bodies or public-IP values, disables redirects, requires HTTPS/TLS validation, and uses bounded connect/read timeouts.
- Added generation-based stale-result protection so a late probe cannot overwrite stop, revoke, or a newer profile's state.
- Added unit and local smoke tests for expected HTTP responses and captive-portal rejection. Network calls and Android build still require CI/device execution.
