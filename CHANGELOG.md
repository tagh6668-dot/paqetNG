# Changelog

All notable changes to this project will be documented in this file.

## [v1.2.0]

### Feat
- Mobile data support: paqet core runs on rmnet/ccmni (Raw IP, no Ethernet header, no router MAC required)
- Auto iptables anti-RST rule (v4+v6) on connect, removed on disconnect — no Termux needed
- Point paqet submodule to tagh6668-dot/paqet (RawIP core + 0x50 wire format, latest upstream)

### Fix
- Cellular fallback no longer emits placeholder MAC 00:00:00:00:00:00 (empty router_mac = RawIP mode)
- Wire-format compat: core synced past alpha.19 to the 0x50 magic-byte protocol

## [v1.1.0]

### Chore
- Update build tooling and dependencies
- Update paqet submodule to v1.0.0-alpha.19 (configurable smux keepalive settings, iterator off-by-one fix)
- Update hev-socks5-tunnel submodule (TCP fastopen support, HevConfig 2.15.0, LwIP and HevSocks5Core updates)

### Refactor
- Refine VPN foreground service manifest
- Settings UI polish and code cleanups

## [v1.0.4]

### Chore
- Update paqet submodule to v1.0.0-alpha.18 (UDP DialContext timeout, UDP strm read/write error handling fixes, MIPS softfloat ABI for CI builds)

## [v1.0.3]

### Feat
- Auto-select newly created or imported profiles as active profile
- Add confirmation dialog when deleting profiles to prevent accidental deletion
- Add KCP configuration options: `conn`, `mode`, `mtu`, and manual mode parameters (`nodelay`, `interval`, `resend`, `nocongestion`, `wdelay`, `acknodelay`)

### Fix
- Fix API level 26 compatibility issues for Android 24+
- Add 16 KB page size alignment flags to paqet and tcpdump binaries for Android 15+ compatibility

## [v1.0.2]

### Feat
- Add Quick Settings tile to turn VPN on/off from the notification center

### Fix
- Add missing and remove unintended paqet and tcpdump build

## [v1.0.1]

### Feat
- Add tcpdump viewer

### Fix
- Update application state based on VPN events
- Restart paqet while user switch the profile

### Refactor
- Improve the log viewer
- Improve the asset stripping based on abi

### Chore
- Changed paqet submodule URL to actual repository
