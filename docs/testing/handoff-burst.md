# Hand-off bursts (Hoard 2.0.1, idle reclaim 2.0.2)

What it checks: a client opening hundreds of Hoard's hand-off links at once (Reveal at start-up) can no longer stall
the phone's storage service or get Hoard killed. Design: ADR 0010, "Bursts of opens"; the `busy:` contract:
[integration](../integration/README.md), contract 11.

## Setup

- Emulator `hoard-burst` (API 36, `-memory 2048 -no-window -no-snapshot -no-audio`), on cachyos-fps. Never a phone
  with Hoard: the debug build replaces the app.
- Samba container (`docs/testing/xmp-sidecars/samba`, `--memory 256m --cpus 1`, port 4458) holding 60 files from
  `docs/testing/tools/burst-files.py`: 20 sidecars (8 KiB), 20 JPEG-sized (3 MiB), 20 RAW-sized (24 MiB). Every byte
  is a function of its offset, so the client checks what it reads.
- `HandoffBurstSetup` (`:native` androidTest) registers the 60 SMB links and 60 local FileProvider links (1 MiB each)
  and grants them, persistable, to the client: the `:core:smb` androidTest APK, its own uid, as Reveal is.
- `HandoffBurstClient` opens N links at once (one thread each), reads a sample, checks it, closes; it reports open
  times, outcomes and the peak of descriptors it held. `retry` retries `busy:` as the contract says (250 ms doubling
  to 4 s, giving up after 30 s).
- `docs/testing/tools/handoff-burst.sh <label> [scenario…]` runs it all, force-stopping Hoard before each scenario (a
  cold start, as when Reveal reopens links after a restart), sampling `dumpsys mount` every 0.5 s (system_server's
  storage service: it hangs when vold does), and grepping logcat. `BURST_OPENS` sets N; `BURST_COLD=1` deletes
  Hoard's small-file copies first.

```sh
ANDROID_SERIAL=emulator-5580 BURST_OPENS=200 docs/testing/tools/handoff-burst.sh after smb smb-retry smb-hold local local-hold
```

Scenarios: `smb` (N opens, read, close), `smb-retry` (the same, retrying `busy:`), `smb-hold` (every descriptor held
5 s), `local`, `local-hold` (FileProvider links), `smb-leak` (2.0.2: a leaking client opens `BURST_LEAK` = 20 RAW
links at once, retrying `busy:`, reads their head and never closes them; then the normal `smb-retry` burst; then it
reads every leaked descriptor's tail again and only then closes them). `HOARD_APK` installs another Hoard build (the
2.0.1 comparison). Raw results: `handoff-burst/<label>-<scenario>.txt`.

## Results (2026-11-15)

**Before**: Hoard 2.0.0 (every SMB open a proxy descriptor, no limit). **After**: this branch (`MAX_READS` = 6,
`BUSY_WAIT_MS` = 1.5 s, small files from a private copy). Bursts stepped up 50 → 100 → 200 on the after build.

| Scenario (200 opens) | Hoard | Outcome | Burst time | `dumpsys mount` mean / max | appfuse opens in system_server | Hoard killed |
| --- | --- | --- | --- | --- | --- | --- |
| smb | 2.0.0 | client died with Hoard | — | 63 / 351 ms | 194 watchdog pauses | **yes** (`ForegroundServiceDidNotStartInTimeException`) |
| smb | 2.0.1 | 158 ok, 42 `busy:` | 10.2 s | 34 / 132 ms | 32 | no |
| smb, cold copies | 2.0.1 | 145 ok, 55 `busy:` | 16.6 s | 26 / 82 ms | 34 | no |
| smb-retry | 2.0.0 | client died with Hoard | — | 48 / 116 ms | 138 watchdog pauses | **yes** |
| smb-retry | 2.0.1 | **200 ok** (37 retries) | 15.2 s | 24 / 62 ms | 61 | no |
| smb-retry, cold copies | 2.0.1 | **200 ok** (39 retries) | 18.3 s | 19 / 32 ms | 60 | no |
| smb-hold | 2.0.0 | 200 ok | 73.8 s | 47 / 138 ms | 204 watchdog pauses, 112 descriptors, 98 threads | no |
| smb-hold | 2.0.1 | 37 ok, 163 `busy:` | 22.7 s | 20 / 46 ms | 6 (6 descriptors held, 31 copies) | no |
| local | 2.0.0 | 200 ok | 1.8 s | 51 / 88 ms | 0 | no |
| local | 2.0.1 | 200 ok | 0.7 s | 15 / 22 ms | 0 | no |
| local-hold | 2.0.0 | 200 ok | 6.5 s | 30 / 89 ms | 0 | no |
| local-hold | 2.0.1 | 200 ok | 6.2 s | 18 / 31 ms | 0 | no |

Stepping up (2.0.1, warm emulator after the first run):

| Opens | smb | smb-retry | smb-hold |
| --- | --- | --- | --- |
| 50 (first run, cold) | 15 ok, 35 `busy:`; mount max 486 ms | 50 ok, 66 retries, 18.0 s | — |
| 100 | 46 ok, 54 `busy:`; mount max 228 ms | 100 ok, 56 retries, 20.0 s | 37 ok, 63 `busy:` |
| 200 | 158 ok, 42 `busy:`; mount max 132 ms | 200 ok, 37 retries, 15.2 s | 37 ok, 163 `busy:` |

In every 2.0.1 run: system_server and vold kept their pids, no `WATCHDOG KILLING` / `Blocked in` line, no Hoard ANR
or crash, `dumpsys mount` never failed, Hoard's threads stayed around 50 (98 before), and the log had one
`HoardHandoff` line per burst instead of one per open, e.g.:

```
open x237 in 13.6 s, 16 concurrent, 37 refused (140 cached, 60 proxied, 0 failed), 0 descriptors open, appfuse open avg 1 ms max 6 ms
```

"16 concurrent" is opens inside Hoard at once, waiting included: Hoard's binder pool (16 threads) caps it.

## Reading the numbers

- 2.0.0 died in both plain SMB bursts: every binder thread of Hoard sat in an SMB call while system_server waited in
  appfuse opens for it, so Hoard's own `startForeground` could not get through in time and Android killed it (and the
  client with it). On the S24 the same pile-up held vold long enough for the `system_server` watchdog. The 2.0.0
  results were measured with the first version of the script, whose "watchdog lines" counted every `Watchdog` line
  (mostly `#openFile might be slow` pauses); 2.0.1's rows count those pauses alone.
- 2.0.1 refuses with `busy:` instead of queueing without bound. A client that follows the contract gets every file
  (the `smb-retry` rows), in about the time a plain burst takes. Without retries, the refused opens are the price of
  never blocking a binder thread for more than 1.5 s.
- `smb-hold` is the worst case on purpose: 6 proxy descriptors held 5 s keep every read slot taken, so the burst's
  other proxied opens are refused until they close (the contract: clients keep at most 6 opens of their own).
  Small files are copies and do not hold a slot once open.
- Local (FileProvider) links never touch appfuse and were already cheap: unchanged.
- Numbers are from an emulator with a throttled container (1 CPU) on a loaded host, not the phone; compare rows with
  each other, not with the S24.

## Idle reclaim (Hoard 2.0.2, 2026-11-15)

What it checks: a client that leaks proxy descriptors (never closes them) no longer locks every other client into
`busy:` (ADR 0010, "Idle reclaim": park after 12 s idle, re-acquire on the next read, retire after 10 min, 12 loopers).

```sh
ANDROID_SERIAL=emulator-5580 BURST_OPENS=60 docs/testing/tools/handoff-burst.sh after-202 smb-leak
HOARD_APK=<2.0.1 debug apk> ANDROID_SERIAL=emulator-5580 BURST_OPENS=60 docs/testing/tools/handoff-burst.sh before-201 smb-leak
```

Same emulator and container as above. The leaking client opens 20 RAW links (24 MiB, proxy descriptors) and retries
`busy:` for 30 s; the normal client then opens all 60 links (20 sidecars, 20 JPEG-sized, 20 RAW) at once, retrying
`busy:` as the contract says, while the leaked descriptors stay open.

| Hoard | Leaking client (20 RAW opens) | Normal client (60 opens, retrying) | Leaked descriptors re-read afterwards | Hoard threads |
| --- | --- | --- | --- | --- |
| 2.0.1 | 6 held, 14 `busy:` after 30 s | **0 ok, 60 `busy:`** (272 retries, gave up after 31.5 s) | 6 ok | 51 |
| 2.0.2 | 16 held (6 at once, the next ones as slots parked every ~12 s), 4 `busy:` after 30 s | **60 ok in 3.9 s** (7 retries; open p50 244 ms, max 3.5 s) | 7 ok, 9 `EIO` (below) | 64 |

In both runs: system_server and vold kept their pids, no watchdog kill, no Hoard ANR or crash, `dumpsys mount` max
57 ms (2.0.2) / 55 ms (2.0.1). Hoard's summary lines (2.0.2):

```
open x76 in 12.0 s, 16 concurrent, 70 refused (0 cached, 6 proxied, 0 failed), 6 descriptors open (6 parked, 0 reacquired, 0 retired), …
open x75 in 5.7 s, 16 concurrent, 11 refused (40 cached, 24 proxied, 0 failed), 10 descriptors open (4 parked, 2 reacquired, 6 retired), …
```

A first 2.0.2 run (same build before the last log wording change) gave the same picture: 12 leaked, normal client 60
ok in 9.0 s (max open 9.0 s), re-read 6 ok / 6 `EIO`.

Regression check at 200 opens on 2.0.2 (compare with the 2.0.1 rows above):

| Scenario (200 opens) | Outcome | Burst time | `dumpsys mount` mean / max | appfuse opens | Hoard threads after |
| --- | --- | --- | --- | --- | --- |
| smb | 155 ok, 45 `busy:` | 9.9 s | 30 / 87 ms | 29 | 53 |
| smb-retry | **200 ok** (22 retries) | 12.2 s | 18 / 27 ms | 61 | 51 |
| smb-hold | 56 ok, 144 `busy:` | 20.2 s | 17 / 29 ms | 6 | 55 |

### Reading the numbers

- 2.0.1: the 6 leaked descriptors held every read slot, so even the normal client's sidecars and JPEGs (which need a
  slot only for the copy) were refused for the whole retry budget. 2.0.2: the leaked slots came back 12 s after their
  last read, so the normal client got all 60 files, well inside the busy retry window.
- The leaking client itself kept getting slots as its earlier descriptors parked (6, then 12, then 16 held), which is
  the point: holding a descriptor no longer means holding a slot.
- The `EIO`s of the re-read are the design, not a fault: 6 of the leaked descriptors were retired when the normal
  burst needed their loopers (12 live descriptors at most, least recently used parked one first), and the client's
  sequential re-read took a slot per descriptor it read, so after 6 the remaining ones found the 6 slots held (by its
  own just-read descriptors) and answered `EIO` after the 1.5 s wait. Re-reads of parked descriptors that found a slot
  returned the right bytes.
- Threads: 12 looper threads (created once, reused) plus the reaper replace 2.0.1's 6 loopers; nothing grows with
  the number of leaked descriptors.
- `smb-hold` holds each descriptor 5 s, under the 12 s idle time, so it behaves as in 2.0.1.
