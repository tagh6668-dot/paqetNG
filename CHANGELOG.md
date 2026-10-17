# Changelog

All notable changes to this project will be documented in this file.

## [v1.2.9]

### Fix
- `ccmni` `ARPHRD 519` on MCI (MTK): libpcap `arptype 519 not supported — falling back to cooked socket` was treated as hard error, aborting `PacketConn` on `ccmni1` (diag `pcap activate err Warning: arptype 519…` with empty capture). Now treat warning as success (fallback `SLL`) and handle `LT 519/0` as cooked raw-IP. Core now on `92f5d35`.

## [v1.2.8]

### Fix
- IPv6 `Sendto` `port 0` with `IPV6_HDRINCL`: `AF_INET6+SOCK_RAW+IPPROTO_RAW+IPV6_HDRINCL` on 4.19 expects `sin6_port=0` — port is inside TCP header. Non-zero triggered `EINVAL` (harness+strace proof), so `PA` on `[2a14::1]:28443` never left `ccmni1`. Core `5078b3f` (also includes `IPV6_HDRINCL` 51→36).

## [v1.2.7]

### Fix
- IPv6 raw send: fix `IPV6_HDRINCL` (was 51/`IPV6_RECVHOPLIMIT`, now 36 via `unix.IPV6_HDRINCL`) and `Sendto` port (was non-zero → `EINVAL` on 4.19; now `0` with port inside TCP header) — `sendto(AF_INET6,SOCK_RAW,IPPROTO_RAW)` returned `EINVAL` so no IPv6 packet ever left `ccmni1` (harness+strace `sendto EINVAL`). Now `PA` on `[2a14:7981:467:9f7::1]:28443` works.
- App startup: `PaqetConfig.ipv6Addr` NPE on old DataStore configs (Gson null bypass) — make nullable + safe `withDefaults()` (v1.2.6 hotfix included).

### Feat
- Dual-stack: when `serverAddr` is `[2a14::1]:28443`, emit `network.ipv6` block on `ccmni1` (`[2a03:...]:0`); IPv4 unchanged. Core now on `5078b3f`.

## [v1.2.6]

### Fix
- Hotfix: `PaqetConfig.ipv6Addr` NPE on old DataStore configs (Gson set missing field to `null` → `withDefaults().copy` NPE).

## [v1.2.5]

### Fix
- Provider: fix `iface` selection on `ccmni/rmnet` (prefer non-`tun` physical iface).
- Core: IPv6 dual-stack — new `ipv6Addr` field + `PaqetYaml` `ipv6:` block when server is `[`bracketed`]` (ParsPack MCI `[2a14::1]:28443`).

## [v1.2.1]

### Feat
- New package name: com.tagh6668.paqetng (rebrand from com.alirezabeigy.paqetng)
- New launcher icon: gift-box on purple-to-blue gradient
- Fixed release signing with bundled keystore secret (signed APKs)

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
