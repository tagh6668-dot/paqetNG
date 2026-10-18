## [v1.3.8]

### Fix
- **Dual-stack TUN** (`PaqetNGVpnService` + `Tun2SocksRunner`): VPN previously created an IPv4-only TUN (`10.0.0.2/30` + `0.0.0.0/0` + Google v4 DNS). IPv6/AAAA traffic bypassed the TUN entirely, going direct and appearing "faster" than tunneled IPv4. Now adds `fd00::2/64` + `::/0` + Google v6 DNS (`2001:4860:4860::8888/8844`) to the Builder and `ipv6: 'fd00::2'` to the hev-socks5-tunnel config, so both families are routed through tun2socks -> SOCKS -> paqet.
- **Hash collision for IPv4-mapped IPv6** (`paqet/internal/pkg/hash/ip_addr.go`): `IPAddr` checked `len(ip)==4` instead of `To4()!=nil`, so 16-byte `::ffff:x.x.x.x` addresses were hashed via the IPv6 XOR path and could collide with distinct v4 hosts. Now uses `To4()` normalization, matching Go's own IP canonicalization. Core bumped to `v1.0.0-alpha.22`.


# Changelog

## [v1.3.7]

### Fix
- **Silent notification**: اتصال VPN/SOCKS ديگر پاپ‌آپ، صدا يا ويبره ندارد. هر دو کانال vpn_channel و socks_channel به IMPORTANCE_LOW (بي‌صدا/بي‌ويبره/setSound(null)) تغيير کردند و NotificationCompat نيز setSilent(true), setOnlyAlertOnce(true), PRIORITY_LOW, VISIBILITY_SECRET دارد. نوتيفيکيشن همچنان به‌صورت ongoing در سايه باقي مي‌ماند (براي Disconnect) اما فقط وقتي خودتان پنل نوتيفيکيشن را باز کنيد ديده مي‌شود. کانال‌هاي قديمي HIGH به‌صورت خودکار مهاجرت (delete+recreate) مي‌شوند.

## [v1.3.6]

### Fix
- **P0 anti-RST dead code** (`PaqetRunner`/`RealDelayTester`): regex parsed `network.ipv4.addr:0` → `lastRawPort` always null, iptables anti-RST never installed → kernel RST killed RawIP connections on ccmni/rmnet. Now parses `server.addr` (`[ipv6]:port` and bare `ipv6`) via `parseServerPortFromYaml`.
- **WiFi false RawIP**: `DefaultNetworkInfo` with empty `routes` gateway returned `router_mac:""` → paqet RawIP on WiFi. Now `isWifiInterface` + `resolveGatewayViaRoot` (ip route/neigh) and delegate to root path instead of silent RawIP.
- **ANR on connect**: `MainActivity.doConnect` used `runBlocking { DataStore.first() }` on Main (×2) + 800ms delay. Now `CoroutineScope(Dispatchers.Main).launch { splitMode/splitApps.first() }`.
- **ensureBinary 16 MB on Main**: `readBytes()` ×2 on Main. Rewritten to `openFd` size check + streaming SHA-256 (8K buf) via `sha256OfAsset/File` + `copyAssetToFile`.
- **IPv6 placeholder** (`PaqetYaml`): `[::]:0` fallback masked missing IPv6. Now `IllegalStateException` fail-fast; caller must refresh network info (`HomeViewModel` now try/catches).
- **IPv6 server detection**: `isIpv6Server` only checked `[`. Now handles bare `::` and `colonCount>=2` heuristic.
- **Tile double-tap race**: `PaqetNGTileService` could start two paqets on quick tap. Added `@Volatile tileBusy` with `try/finally`, `isRunning` guard and batched `runBlocking`.
- **RealDelayTester iptables race + leak**: 10 parallel testers raced `iptables -D`. Now ref-counted `antiRstRefCount`/`antiRstLock` + `cleanupOrphanedDirs()` for `paqet_test_*`.
- **Notification channel reset**: `PaqetNGVpnService`/`SocksNotificationService` deleted user-muted channel each launch. Now `if (getNotificationChannel==null) create` only.
- **Maintainability**: `PaqetConfig.withDefaults` verbose `try/catch` noted (kept to avoid regression).

## [v1.3.5]


### Feat
- Test real delays v2: parallel x10 isolated tester (no VPN disconnect needed). Each config runs in its own temp dir with its own paqet binary copy + ephemeral SOCKS port (28k range) + separate anti-RST iptables, so the active VPN/SOCKS session is never touched. Concurrency is 10 (Semaphore). Timeout and check URL now both follow Settings > Connection check (seconds 3-60, coerced to ms), likewise SOCKS LAN setting and log level. Results update live per-config (pill + spinner) via StateFlow; list auto-sorts low->high after batch (timeouts last).
- Toolbar cleanup: removed duplicate toolbar Speed button — only the 3-dot overflow menu entry remains ("Test real delays" / "Cancel real delay test" during run). Previous duplicate ran the same action twice.

## [v1.3.4]

### Feat
- Test real delays: new "Test real delays" action on Home tests every saved config for real via paqet SOCKS (starts paqet per config, polls SOCKS until ready, GETs the connection check URL through that SOCKS, records ms). Shows per-config pill (green <300ms, secondary 300-800ms, red timeout), inline spinner while testing one, batch progress row with cancel/clear. Results sort low->high (timeouts last, untested middle). Requires root and disconnected state; uses your SOCKS port, LAN setting and connection-check URL/timeout. Batch start is available from toolbar Speed button and overflow menu.


All notable changes to this project will be documented in this file.

## [v1.3.3]

### Feat
- Export all configs: new toolbar action copies all profiles as newline-separated `paqet://` links to clipboard (one per line) for batch share/restore.
- Import from clipboard: now imports multiple `paqet://` links at once (newline/space-separated, JSON array, or base64-encoded subscription blob). Shows count snackbar ("Imported N configs").

## [v1.3.2]

### Fix
- Fix notification never appearing on MIUI/Android 13+: `POST_NOTIFICATIONS` was never requested so channel stayed `importance=NONE` (MIUI blocks shade). Now `MainActivity` requests the runtime permission proactively on first launch and again on Connect; if denied a toast explains Disconnect will be hidden. Both `vpn_channel` and `socks_channel` upgraded from `IMPORTANCE_LOW` to `HIGH` and auto-migrate existing installs (delete+recreate) so ongoing notification is visible on MIUI 14.

## [v1.3.1]

### Feat
- Notification disconnect: in VPN mode the foreground notification's Disconnect button now stops `paqet` directly (previously only tore down TUN); in SOCKS-only mode a new ongoing notification is shown so the user can disconnect without opening the app. Quick Settings tile also handles both modes and notification shade.

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
