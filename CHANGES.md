# Partial Refresh Refactor — Change Log

## Summary

Replaced the multi-patch partial refresh strategy with a single-bounding-rectangle
approach, deleted the independent clock-update ticker, and added area/count/time-based
anti-ghosting controls.

---

## Before

| Behaviour | Detail |
|---|---|
| Per-cycle sends | Up to N+1 (one per changed row + clock zone) |
| Clock updates | Independent `clockUpdateTask` firing at minute boundaries, racing with `refreshTask` |
| Full/partial decision | Row count threshold (`PARTIAL_REFRESH_THRESHOLD = 5`) |
| Anti-ghosting | None |

## After

| Behaviour | Detail |
|---|---|
| Per-cycle sends | Exactly 1 (`sendBitmap` or `sendPartialBitmap`) |
| Clock updates | Folded into `refreshTask`; clock zone included in patch when minute rolls |
| Full/partial decision | Bounding-rect area fraction (`MAX_PARTIAL_AREA_FRACTION = 0.60`) |
| Anti-ghosting | `MAX_CONSECUTIVE_PARTIALS = 30` partials, then full; forced full every 15 min |

---

## Timing — one tick-only refresh cycle

_Fill in after collecting `[PERF]` logs from device:_

```
grep "\[PERF\]" logcat.txt
```

| Metric | Before | After |
|---|---|---|
| EPD calls per cycle (no data change, minute rolled) | 2 (clock + footer) | 1 (clock zone only) |
| EPD calls per cycle (2 rows changed) | 3 (clock + 2 rows) | 1 (union rect) |
| EPD calls per cycle (many rows changed) | 1 full (threshold exceeded) | 1 full (area exceeded) |
| `sendPartialBitmap` ms (clock zone only, ~13% × 14%) | _TBD_ | _TBD_ |
| `sendPartialBitmap` ms (2 rows + clock) | _TBD_ | _TBD_ |
| Panel visible update after partial | _TBD_ | _TBD_ |

---

---

## Display mode investigation — closed

Tested all four modes (0–3) on device 2026-05-27. All modes behave identically.
`setDisplayMode` has no effect on this firmware. Dead end — not called in
production code. See `docs/epd-behavior.md §6` for the full finding.

---

## Edge cases addressed

1. First run → forced full refresh
2. Arrivals list size changed → forced full refresh
3. List reshuffled (>50% shortName mismatches) → forced full refresh
4. `null` fields in `BusArrival` → `Objects.equals` throughout `rowChanged`
5. Empty arrivals list → clock zone still updates on minute roll
6. Offline→online transition → forced full refresh to clear OFFLINE indicator
7. `ACTION_SET_STATION` during fetch → `lastStation` nulled, forcing full on next cycle
8. Bitmap pixel-buffer sharing → allocation size check before `patch.recycle()`
9. EPD x/width alignment → `alignToEightPixels()` applied to every partial rect
