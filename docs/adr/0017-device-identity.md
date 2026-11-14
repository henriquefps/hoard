# ADR 0017: One device identity for Hoard and Reveal

- **Date**: 2026-11-14
- **Status**: Accepted (hoard-server 0.7.0, Hoard 2 phase 1d)
- **Amends**: ADR 0013 (additions under `/v1`, written there in "Physical devices (hoard-server 0.7.0)")

This ADR is meant to be implemented from on its own: "Client calls" lists exactly what Hoard and Reveal send.

## Context

Since ADR 0013 every **app installation** pairs with the server on its own: it scans a QR code, gets its own API key
(`rvk1_…`) and a `deviceId`, and the admin page lists it as one "device". With Hoard pairing next to Reveal, one phone
shows up twice ("Pixel 8" for Reveal, "Pixel 8" for Hoard). The server will soon do things per **phone**: back up a
phone's folders once (not once per app), show "this phone's folders", report which phone holds a file. It needs to
know that the two installations are the same hardware.

What was decided before this ADR: each app keeps pairing on its own (its own key, revocable alone, as today), and both
register the **same device id** so the server can group them.

Constraints:

- Hoard (`dev.hfps.hoard`) and Reveal (`dev.hfps.reveal`) are signed with **different keys**: no `signature`-level
  permission, no shared user id, no shared private storage.
- No IPC between the apps for this (either may be installed alone, in any order, and either may be updated first).
- The owner's UX: **one tap**, at most, and never a question when there is nothing to group.

### Ways to derive the same id, evaluated

| Option | Verdict |
| --- | --- |
| `Settings.Secure.ANDROID_ID` | Since Android 8 it is scoped per (app-signing key, user, device): **different** for Hoard and Reveal. Rejected. |
| `Build.SERIAL` / IMEI / MAC | Need privileged permissions since Android 10 (or return placeholders). Rejected. |
| A file both apps write on shared storage (Downloads, MediaStore) | Needs storage permission or a document the user picks; the user can delete it; a second phone restored from backup copies it. Fragile and visible. Rejected. |
| An Android `Account` / `ContentProvider` exported by one app | Is IPC with extra permissions and install-order problems. Rejected. |
| Matching hardware on the server alone (same model ⇒ same phone) | Two phones of the same model (the owner's and a partner's) would merge silently. Rejected as automatic. |
| **The server proposes, the user confirms** (matching model + one tap in the app) | Chosen. |
| A merge on the admin page | Kept as the fallback (no app needed, and to undo a wrong answer). |

## Decision

### Model

- A **device** stays what it is in ADR 0013: one app installation with its key. `deviceId` keeps its meaning in every
  existing payload (records, conflict copies, uploads, `GET /v1/devices`).
- A **physical device** groups the devices that run on the same hardware. Every device belongs to exactly one. Its id
  (`physicalDeviceId`, a lower-case UUID) is the **shared device id** both apps register.
- A new pairing starts a physical device of its own whose id **equals the new `deviceId`**. Existing devices get the
  same at the upgrade (migration 0005: one physical device per device, same id). So every device id is also a valid
  physical device id until it is grouped, and nothing about existing Reveal keys changes.
- Each device also reports `app` (`"hoard"`, `"reveal"`, or another `[a-z0-9-]{1,32}` name), `manufacturer` and
  `model` (`Build.MANUFACTURER`, `Build.MODEL`, at most 64 characters each). These are for matching and display; they
  are never trusted for anything else.
- A physical device with no device left (its last device moved away) is deleted. A revoked device stays in its
  physical device (shown as revoked): revoking does not regroup.

### How both apps end up with the same id: the server proposes, the user confirms

1. The first app pairs as today (plus `app`, `manufacturer`, `model`). It is alone: no question.
2. The second app pairs. The `POST /v1/pair` answer includes `candidates`: the other physical devices on the same
   `platform` that have at least one live device and **no device of this app**, the ones with the same
   `manufacturer` + `model` (case-insensitive) first, marked `match: "model"`.
3. If exactly one candidate has `match: "model"`, the app shows **one question** right after pairing:
   "Is this the same phone as **Reveal** on **Pixel 8**?" → **Same phone** / **Another device**. "Same phone" calls
   `POST /v1/device/claim` with that candidate's `physicalDeviceId`; "Another device" calls nothing. Several model
   matches: the same question as a list ("Which of these is this phone?", plus "None"). No model match: no question.
4. Either app can open the same choice later (Settings → Server → "This phone": the current group and the
   candidates), and the admin page can move any device (below). A wrong answer is undone the same way.

Why this one: it needs no permission and no IPC, works in any install order (the one asking is always the one that
pairs second), costs one tap in the common case (one phone of that model), and never merges two phones without a
person saying so.

**Existing Reveal installations.** Reveal devices paired before 0.7.0 have no `app` / `model` until the updated
Reveal reports them (`PATCH /v1/device`, silently, on its next sync). Until then they are still candidates (the
server knows them only as `platform = android`), just without `match: "model"`; Hoard then shows no automatic
question, and the owner can group them from Hoard's settings or the admin page. Once the updated Reveal has reported
its model, Hoard's pairing finds it as a model match.

### Client calls

All with the device's own key (`Authorization: Bearer rvk1_…`). `GET /v1/server` lists `"device-identity"` in
`features`; without it (an older server) the app skips all of this.

**1. Pairing** (both apps): `POST /v1/pair` gains three optional fields; older servers ignore them.

```json
{"token": "…", "deviceName": "Pixel 8", "platform": "android", "appVersion": "1.11.0",
 "app": "hoard", "manufacturer": "Google", "model": "Pixel 8"}
```

`201` adds `physicalDeviceId` and `candidates` to the existing fields:

```json
{"deviceId": "5c1d…", "apiKey": "rvk1_…", "serverId": "…", "serverName": "Hoard server",
 "physicalDeviceId": "5c1d…",
 "candidates": [{"physicalDeviceId": "a0e4…", "name": "Pixel 8", "match": "model",
                 "devices": [{"id": "a0e4…", "name": "Pixel 8", "app": "reveal", "platform": "android",
                              "manufacturer": "Google", "model": "Pixel 8"}]}]}
```

**2. Reporting app and model** (an already-paired app, once after updating, and whenever they change):
`PATCH /v1/device` with any of `{"app", "manufacturer", "model"}` → `200` with the device view (below).

**3. Reading** "this phone": `GET /v1/device` →

```json
{"deviceId": "5c1d…", "name": "Pixel 8", "platform": "android", "app": "hoard",
 "manufacturer": "Google", "model": "Pixel 8",
 "physicalDevice": {"id": "a0e4…", "name": "Pixel 8",
                    "devices": [{"id": "a0e4…", "name": "Pixel 8", "app": "reveal", "platform": "android",
                                 "manufacturer": "Google", "model": "Pixel 8"},
                                {"id": "5c1d…", "name": "Pixel 8", "app": "hoard", "platform": "android",
                                 "manufacturer": "Google", "model": "Pixel 8"}]},
 "candidates": []}
```

`physicalDevice.devices` lists the live devices of the group (this one included); `name` is the name of its oldest
live device. `candidates` as in pairing (empty once grouped, unless other groups also qualify).

**4. Claiming**: `POST /v1/device/claim` `{"physicalDeviceId": "a0e4…"}` moves **this** device into that physical
device → `200` with the device view. `{"physicalDeviceId": null}` moves it out to a physical device of its own (id =
its `deviceId` when free, else a new UUID). Errors: `404 not_found` (no such physical device), `409 conflict` with
`details.reason = "same_app"` when the target already has a live device of the same `app` (two Hoards cannot share one
phone; revoke the old one first), `400 invalid_request` for a malformed id. Claiming the group it is in is a no-op
`200`.

**5. Listing**: `GET /v1/devices` entries gain `physicalDeviceId` and `app` (`null` when not reported).

What each app stores: its own `deviceId` and key as today, plus the last `physicalDeviceId` it read (refreshed with
`GET /v1/device` when the app opens the settings page and after a claim). Per-device features later (backups, "this
phone's folders") use `physicalDeviceId`.

### Admin page

Devices → the list is grouped by physical device: one card per phone with its name, model and its apps (each app row:
name, app, version, last seen, revoke). Each device has **Move to…** (another physical device, or "its own"), which
calls `POST /admin/api/devices/{id}/physical` `{"physicalDeviceId": "…" | null}` with the same rules as a claim
(`same_app` refused). `GET /admin/api/devices` entries gain `physicalDeviceId`, `app`, `manufacturer`, `model`.

### Storage

Migration `0005_culling_devices.sql` (with ADR 0016's tables): `physical_devices (id PRIMARY KEY, created_at)`, and
`devices` gains `physical_id` (references `physical_devices`; set for every device by the backfill and by every
pairing), `app`, `manufacturer`, `model` (nullable: not reported). Backfill: one physical device per existing device, with the device's id.

## Consequences

- Nothing changes for released Reveal versions: they pair, push and pull as before; their device is a physical device
  of its own until someone groups it.
- Each app keeps its own key: revoking Reveal's key leaves Hoard paired, and the reverse.
- Two phones of the same model are never grouped without a tap; a wrong tap is undone from either app or the admin
  page.
- A factory-reset phone pairs as a new device (new key); the question then offers the old physical device (same
  model), whose devices the owner can revoke.
- The server never learns more than the model name the apps already show in their device name.

## Alternatives considered

See the table in "Context". Also: a pairing QR code bound to a physical device (the admin picks "Add an app to Pixel
8" before showing the code). It works without a question in the app but moves the decision to the admin page, which
the owner opens rarely; it can be added later as a pairing token option without changing anything here.
