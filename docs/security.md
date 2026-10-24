# Security baseline

- Do not place secrets or private keys in source control, diagnostics, crash reports, or test fixtures.
- Do not execute a newly discovered engine or configuration automatically.
- Treat repository metadata and configuration as untrusted input.
- A route must pass security and compatibility gates before performance scoring.
- Do not report a connected state based only on a process-start result or server ping.
- Keep diagnostics opt-in if any data leaves the device; local diagnostics must redact credentials.
- A real VPN service must protect its upstream tunnel socket from routing back into its own TUN interface and must define fail-closed behavior explicitly.
- GPL and other upstream obligations require a per-component review before redistribution.
