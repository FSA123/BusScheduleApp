# EPD Driver Behavior — EPC3566 / Geniatech el133sdk

Derived from: `EpdConnector.java`, Geniatech EPC API doc v0.2 (2026-05-13),
and the CLAUDE.md integration notes. Updated as new data is collected from
`[PERF]` logs in the field.

---

## 1. Does `sendPartialBitmap` block?

**Answer: No — it returns early.**

The SDK contract (§2.2 of the API doc) says a return value of `0` means
*"the EPD service has received the request."* The panel refresh itself is
asynchronous and takes 1–4 seconds (waveform-dependent). The Java call
returns before the panel has finished updating.

**Implication:** the caller is responsible for not sending a second frame
until the first one completes. The API doc states in red text for every
send method:

> "Please call this method after the EPD image refresh is completed."

No "refresh complete" broadcast is documented. Our strategy: the refresh
interval (minimum 10 s, typical 30–60 s) is far longer than the EPD's
worst-case refresh time, so we rely on the schedule gap rather than a
completion signal.

---

## 2. What happens if `sendPartialBitmap` is called while a previous call is pending?

**Observed behavior on IT8951-class drivers (unconfirmed on EPC3566):**

The EPD controller typically handles this in one of three ways depending on
firmware:

| Firmware behavior | Result |
|---|---|
| Queue | Both calls execute sequentially; the total wall-clock time is additive (~20–30 s × N). Looks like the panel "stalls." |
| Drop | Only the first call executes; subsequent calls are silently discarded. Second zone never updates. |
| Coalesce | Driver computes the bounding union of both regions and executes one large partial. Defeats the area savings. |

**Mitigation in this codebase:** `BusScheduleService` sends at most ONE
`sendPartialBitmap` (or one `sendBitmap`) per refresh cycle, and the refresh
interval is always ≥ 10 s. The `[PERF]` log for each call records elapsed ms;
if you see `ms > 30000` for a full refresh, the panel is still busy.

---

## 3. Are there x/width alignment requirements?

**Status: unconfirmed for EPC3566; likely yes based on hardware lineage.**

Most IT8951-based and Rockchip RGA-routed EPD controllers require the
x-offset and width to be multiples of 4 or 8 pixels (the pixel pipeline
processes data in 4-byte or 8-byte aligned bursts).

**What the SDK docs say:** alignment is not explicitly mentioned in the
EPC API doc v0.2. No `IllegalArgumentException` is thrown for unaligned
values at the Java level — misalignment either silently passes or causes
visual corruption at the panel level.

**Our mitigation:** `alignToEightPixels()` in `BusScheduleService` snaps
every partial rect left edge down and right edge up to the nearest multiple
of 8 before calling `sendPartialBitmap`. This is always safe (it expands
the region slightly).

**To confirm:** watch `[PERF]` logs for `sendPartialBitmap` calls where
`sent=true` but no visible panel update occurs — that indicates silent
drop due to alignment, and the align step can be tuned from 8 → 4 or 8 → 16.

---

## 4. What does the return value mean?

From API doc §2.3:

> `0: means the EPD service has been received.`

| Return | Meaning |
|---|---|
| `0` | Request accepted by the EPD service; panel will refresh asynchronously |
| `< 0` | Error (service busy, invalid bitmap, Binder parcel overflow, etc.) |
| `RemoteException` | Binder died — `onServiceDisconnected` will fire and `EpdConnector` will auto-rebind |

**Common non-zero values seen in practice:**
- `-1`: generic service error (bitmap too large for Binder transport, or panel not ready)
- If a 2560×1440 ARGB_8888 bitmap (~14 MB uncompressed) overflows the Binder transaction
  buffer, `sendImageBitmap` returns `-1`. Try `sendImage(String path)` with a pre-written
  PNG on `/sdcard/` as an alternative if full-frame Bitmaps keep failing.

---

## 5. Timing baseline (`[PERF]` log reference)

Collected values to fill in after first field run:

| Operation | Expected | Measured |
|---|---|---|
| `render` (full frame, 2560×1440) | 50–150 ms | _TBD_ |
| `sendBitmap` call return (full frame) | < 5 ms | _TBD_ |
| Panel visually done after `sendBitmap` | 1–4 s | _TBD_ |
| `sendPartialBitmap` call return | < 5 ms | _TBD_ |
| Panel visually done after `sendPartialBitmap` | 1–3 s | _TBD_ |

Fill these in by grepping `adb logcat | grep "\[PERF\]"` during a live run.

---

## 6. Display mode investigation (`setDisplayMode`)

### API doc — section 2.23 (quoted verbatim)

> **setDisplayMode(int mode)**
> Set display mode.
> Parameters: mode — display mode

That is the entire documentation. No description of what the four values do.

### What is known

- `setDisplayMode` is global state — it persists until the next call or
  service restart. All subsequent `sendImage` / `sendpartImage` calls use
  whatever mode is currently set.
- There are exactly 4 valid values (0, 1, 2, 3), inferred from typical
  EPD waveform tables for IT8951-class controllers:
  - **Mode 0** — likely INIT (full clear, slow, no ghosting, used once at boot)
  - **Mode 1** — likely DU (Direct Update, fast, binary b/w only, heavy ghosting)
  - **Mode 2** — likely GC16 (16-level grayscale, slower, low ghosting, best quality)
  - **Mode 3** — likely GL16 / A2 (varies by panel — fast partial, some ghosting)
  These are guesses based on Waveshare/IT8951 convention. Actual behavior
  must be confirmed empirically.
- `setEPDRippeMode` is **not supported** on EPC3200 (capability table, p10
  of EPC API doc v0.2). Not an option.
- `sendStream` is **not supported** on EPC3200. Not an option.

### Empirical result — confirmed 2026-05-27

All four modes (0, 1, 2, 3) produced identical behavior: same refresh speed,
same visual quality for both full and partial updates. `setDisplayMode` has
no observable effect on this firmware/panel combination.

**Conclusion:** the EPC3566 TCON firmware either ignores the mode parameter
or only implements a single waveform. No action needed — the panel uses its
built-in default and `setDisplayMode` is not called in production code.
The wrapper method is kept in `EpdConnector` in case a future firmware update
activates mode switching.
