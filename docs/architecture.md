# Architecture

- `core`: platform-independent models and adaptive route selection/security gates.
- `app/profile`: bounded JSON profile inspection and private profile storage.
- `app/vpn`: Android `VpnService` lifecycle host and `SingBoxEngine` boundary.
- `app`: Compose UI and VPN permission workflow.

## Current data path

The current version does **not** have a working data plane. The service requests the platform VPN consent and starts a foreground lifecycle host, but `NativeSingBoxEngine` intentionally fails closed before creating a TUN. The native libbox `PlatformInterface` and command-server integration must be implemented before any connection can be considered active.

## Planned data path

UI -> profile store -> native config validation -> `VpnService` -> libbox platform adapter (`openTun`, `protect`/socket control, notification callbacks) -> sing-box routing/outbound engine -> actual traffic probes -> verified status.
