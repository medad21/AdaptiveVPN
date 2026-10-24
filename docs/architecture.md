# Architecture

- `core`: platform-independent models, adaptive route selection/security gates, failure classification, bounded recovery (`RecoveryCoordinator`), `ProtocolRegistry`, and path scoring.
- `app/profile`: bounded JSON profile inspection and private profile storage.
- `app/vpn`: Android `VpnService` lifecycle host and `SingBoxEngine` boundary.
- `app`: Compose UI and VPN permission workflow.

## Current data path

The current version does **not** have a working data plane. The service requests the platform VPN consent and starts a foreground lifecycle host, but `NativeSingBoxEngine` intentionally fails closed before creating a TUN. The native libbox `PlatformInterface` and command-server integration must be implemented before any connection can be considered active.

## Planned data path

UI -> profile store -> native config validation -> `VpnService` -> libbox platform adapter (`openTun`, `protect`/socket control, notification callbacks) -> sing-box routing/outbound engine -> actual traffic probes -> verified status.

## Control plane (core module)

```
observations (adapters) -> FailureClassifier -> ConnectionFailure(kind, confirmed)
                                                       |
routes <- ProtocolRegistry (APPROVED only) --> RecoveryCoordinator --> RecoveryDecision
                                                 |  BackoffPolicy          TryRoute / WaitForNetworkChange /
PathScoreBoard (per network class) --------------+  CircuitBreaker         WaitAndRetry / GiveUp
                                                    Hysteresis
```

- The core never touches Android APIs or the network. Adapters in `app` gather observations, perform the attempts the coordinator returns, verify real traffic, and report results back.
- `controlProbes` must be measured outside the tunnel against independent destinations; without them the classifier will not mark a server or tunnel failure as confirmed.
- Recovery is bounded by `maxAttemptsPerEpisode` and `maxEpisodeDurationMs`. After `GiveUp`, a new episode needs an external trigger (OS network change or a user action).
