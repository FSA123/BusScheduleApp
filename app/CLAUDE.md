# Bus Schedule EPD App — Integration Handoff for Claude Code

You are continuing work on an Android app that displays bus arrival schedules on an external e-paper display. The app is ~90% complete. There is **one specific, well-understood integration issue** to fix, plus a few small adjustments that follow from it. This document gives you the full context so you can proceed precisely.

**Treat this document as authoritative.** It supersedes any assumption you might form from a file alone — especially around the EPD service contract, the meaning of return values, and what "broken" looks like vs. what "working" looks like.

---

## 1. Hardware and runtime environment

### 1.1 The board

- **Android board**: Geniatech RK3566 EPC Board, PCB marking `RKS240717_VL13`
- **Display board**: separate EPC e-paper display board (probably EPC3566 — see §1.4), connected via a wide ribbon (data — likely SPI or UART) and a 4-wire power cable
- **SoC**: Rockchip RK3566 (quad-core Cortex-A55)
- **Android version**: 11 (API 30)
- **Build fingerprint**: `Geniatech/enjoytv/enjoytv:11/V011S003_20260421`
- **SELinux mode**: `permissive` (confirmed via `/proc/cmdline`). This matters — see §6.

### 1.2 Display architecture (read this carefully — do NOT try to "fix" it)

The EPD is **not** an Android display in the SurfaceFlinger / Hardware Composer sense. There is no `/dev/ebc`, no `/sys/class/epd`, no in-kernel framebuffer driver for the EPD, and there never will be. Confirmed empty:

```
adb shell ls /dev/ | grep -iE 'ebc|epd|eink'        → nothing
adb shell ls /sys/class/ | grep -iE 'ebc|epd|eink'  → nothing
adb shell dmesg | grep -iE 'ebc|epd|eink|panel'     → nothing
```

The architecture is intentional:

```
[Android UI / SurfaceFlinger] → HDMI output (developer/debug only)

[Your app: BusScheduleService]
    │
    │  Renders schedule to a Bitmap
    │  Calls epdManager.sendImage(bitmap)
    ▼
[com.geniatech.epc.core / com.geniatech.el133sdk.epdService]   (Android service on the same board)
    │
    │  Serializes the bitmap, transmits over ribbon to the EPD board
    ▼
[EPD board MCU]  →  drives the e-paper panel
```

Therefore:
- `vendor.hwc.device.primary = HDMI-A` is **correct and intended**. Leave it alone.
- `dumpsys display` showing only a 1920×1080@60 "Built-in Screen" is **correct**. The EPD will never appear there.
- Do not propose modifications to HWC, device-tree, kernel drivers, or display routing. The EPD is reached only via the Geniatech Android service. This is the same pattern Boox, reMarkable, and Kindle Fire use.
- The HDMI output exists as a developer convenience to view Android's regular UI for debugging. The user can ignore it for production.

### 1.3 Geniatech EPC software stack (preinstalled, working)

| Package | Location | Role |
|---|---|---|
| `com.geniatech.epc.launcher` | `/system_ext/priv-app/` | Custom home screen |
| `com.geniatech.epc.service` | `/system_ext/priv-app/` | System app — owns user-facing APIs, binds to epdService |
| `com.geniatech.epc.core` | `/system_ext/priv-app/` | Hosts `com.geniatech.el133sdk.epdService` (the actual EPD bridge) |
| `com.geniatech.epc.helper` | `/system_ext/priv-app/` | Helper utilities |
| `com.geniatech.autotest` | `/system_ext/priv-app/` | Factory diagnostic |

Verified via `dumpsys`:
```
ServiceRecord{... u0 com.geniatech.epc.core/com.geniatech.el133sdk.epdService}
  Client AppBindRecord{... ProcessRecord{... com.geniatech.epc.service/1000}}
```

The service is running. The system app is bound to it. Our app needs to bind to it as well using the public SDK contract.

### 1.4 EPD model identifier

The system property `com.geniatech.epcmodel` identifies which EPD panel is attached. On this board it has been set to `EPC3566` via Geniatech's `RKDevInfoWriteTool` (factory provisioning step — already done; do not modify). Verify with:

```
adb shell getprop com.geniatech.epcmodel
→ EPC3566
```

**Caveat**: the Geniatech EPC API documentation v0.2 (dated 2026-05-13) lists capability matrices for these models:
`EPC2003, EPCMT1330, EPC101, EPC1330C, EPC1330G, EPC2530, EPC2530K, EPC312C, EPC312G, EPC2850, EPC3200, EPC1330`

**EPC3566 is not on the list.** This is unresolved. Either (a) the doc is older than this panel and 3566 simply hasn't been added, or (b) "EPC3566" is the board name and the panel attached is one of the listed models. For implementation purposes, treat the SDK's `sendImage(Bitmap)` and `getEPDInfo()` methods as supported (they appear in every panel's row in the matrix). Do not call advanced methods (`sendImageAddMagic`, `sendStream`, `sendImageWithDetails`, `setImageAdjustment`, etc.) without first confirming with the user.

---

## 2. The Geniatech EPD SDK — service contract

This is the **authoritative spec for talking to the EPD**, derived from the Geniatech EPC API documentation PDF (rev 0.2, 2026-05-13). Trust this section over any assumption.

### 2.1 How to bind

The service is bound by **explicit ComponentName**, not by action. There is no action-based intent filter — `query-services -a com.geniatech.el133sdk.EpdManager` returns "No services found". Implicit intents will fail silently.

```java
Intent intent = new Intent();
intent.setComponent(new ComponentName(
    "com.geniatech.epc.core",
    "com.geniatech.el133sdk.epdService"   // note: lowercase 'e' in epdService
));
context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
```

In `onServiceConnected`:
```java
epdManager = EpdManager.Stub.asInterface(service);
```

`EpdManager` is the AIDL interface, **package `com.geniatech.el133sdk`**, descriptor `"com.geniatech.el133sdk.EpdManager"`. It must come from the SDK's `aidl/com/geniatech/el133sdk/EpdManager.aidl` file (see §3.1 below). **Do not hand-write this stub** — using a hand-written stub with mismatched signatures is what caused our previous false-failure debugging.

### 2.2 Method signatures we need

From sections 2.1, 2.3, 2.5, 1.4 of the API doc:

```java
// Section 2.3 — push a Bitmap directly. Returns 0 on success.
int sendImage(Bitmap bitmap);

// Section 2.5 — push an image from disk. Returns 0 on success.
int sendImage(String imagePath);   // .bmp .png .jpg .jpeg .webp

// Section 2.1 — request EPD info. Returns void; result delivered by broadcast.
void getEPDInfo();
```

**Return code convention** (per the doc, section 2.3):

> `0: means the EPD service has been received.`

This is **success**, not failure. Treat `result == 0` as "request accepted by the service for asynchronous processing". Negative values or `RemoteException` are failure. Don't replicate the earlier mistake of treating `0` as "silent error".

### 2.3 The async result pattern (critical)

Several methods are fire-and-forget. The synchronous return code only tells you the request was accepted; the actual result arrives later via an Android `BroadcastReceiver`. `getEPDInfo()` is the canonical example:

```
Call:       epdManager.getEPDInfo()                              → returns void
Broadcast:  android.epdservice.action.RSP_GETDEVICEINFO
Extras:
  String  panelName     → e.g. "EPC3566"
  int     panelW        → panel width in pixels
  int     panelH        → panel height in pixels
  String  firmware      → TCON firmware version
  int     vcom          → TCON VCom value
  int     temperature   → TCON temperature in °C
```

To consume:
```java
private final BroadcastReceiver epdInfoReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        String panelName = intent.getStringExtra("panelName");
        int w = intent.getIntExtra("panelW", -1);
        int h = intent.getIntExtra("panelH", -1);
        // ...
    }
};

// Register before calling getEPDInfo:
IntentFilter f = new IntentFilter("android.epdservice.action.RSP_GETDEVICEINFO");
context.registerReceiver(epdInfoReceiver, f);

// Trigger the query:
try { epdManager.getEPDInfo(); } catch (RemoteException e) { ... }

// Unregister on destroy:
context.unregisterReceiver(epdInfoReceiver);
```

If your code doesn't need panel info, **just delete the getEpdInfo call** — it was diagnostic.

### 2.4 Ordering constraint — "wait for refresh complete"

The doc states in red text for every send method:

> "Please call this method after the EPD image refresh is completed."

E-paper refreshes take 1–4 seconds depending on waveform mode. Calling `sendImage` again while the previous frame is still rendering is unsafe — at best the second call is dropped, at worst it causes ghosting. Our `BusScheduleService` refreshes every 30 seconds, so this isn't a problem for us in normal operation. **Do not** add timer-based refresh faster than ~5 seconds without checking for a "refresh complete" broadcast (none is documented in the sections we've read — if needed, search the rest of the doc or ask Geniatech).

### 2.5 Other available SDK methods (informational only — do not call without explicit request)

From the API doc summary (section 1.4):

```
getEPDInfo()                       → see §2.3
sendImage(String|Bitmap)           → see §2.2
sendpartImage(...)                 → partial refresh
sendImageAddMagic(...)             → "magic" image effect (advanced)
sendpartImageAddMagic(...)
sendpartImageWithMask(...)
sendpartImageWithMaskAddMagic(...)
setImageAdjustment(...)
sendpartImageBitmap(...)
sendImageWithDetails(path, x, y, w, h, hue, sat, bright, contrast, gamma)
upgradeTCON(String path)           → upgrade EPD firmware (dangerous)
setEPDScreenRotate(int degree)     → 0, 180, or -1 (auto via gravity sensor)
getEPDScreenRotate() → int
getTCONTemperature() → int (°C)
setAutoRefrushTime(...)            → note: "Refrush" is the doc's typo, not a typo here
isOpenAutoRefrushTime()
setEPDRippeMode(...)               → "Rippe" is the doc's typo for "Ripple"
getEPDRippeMode()
clSrc()                            → clear screen
setDisplayMode(...)

// System interface (section 3):
goToSleep(int sleepSec), setOSReboot(), getServiceVersion() → String,
setLedOn(int brightness), setLedOff(), setWifiOn(), setWifiOff(),
forgetWifi(), getWifiCountryCode(), setWifiCountryCode(),
setHotspotOn(), setHotspotOff(),
getSerialNumber(), getBuildNumber(),
setNTPServer(...), getNTPServer(),
setTimeZone(...), setSystemTime(...),
InstallAPK(...), setOSRebootTime(...),
isShowNavigationBar(boolean), isShowStatusBar(boolean),
screenshot(String outputImgPath) → int,
fwUpgrade(...),
getBatteryLevel() → int
```

Capability varies by panel model (see API doc section 2.2). Not every method works on every panel.

### 2.6 Signing note

Per API doc section 1.2: *"The 'systemrkkey.jks' in the project's root directory is the system signature."*

The SDK sample is signed with the Rockchip platform key. On a SELinux-enforcing build, our app may need the same signature to bind to the service. On this dev board SELinux is **permissive**, so the bind currently works without signing. Production builds may need the keystore — note this for the user but do not implement signing unless asked.

---

## 3. Project state

### 3.1 What lives where

The relevant files for the EPD integration are concentrated in one package: `ro.smarttrans.busschedule.epd`. The rest of the project (API client, parser, renderer, cache, service, boot receiver, manifest, gradle) is **stable and should not be touched** for this work unless a build error specifically points there.

```
app/
├── build.gradle
├── src/main/
│   ├── AndroidManifest.xml
│   ├── aidl/                                       ← may not exist yet — create it
│   │   └── com/geniatech/el133sdk/
│   │       └── EpdManager.aidl                     ← TO ADD from Geniatech SDK
│   └── java/ro/smarttrans/busschedule/
│       ├── BusScheduleApp.java                     ← OK
│       ├── BootReceiver.java                       ← OK
│       ├── ui/MainActivity.java                    ← OK
│       ├── api/
│       │   ├── ApiClient.java                      ← OK (3 retries, 2s/4s backoff)
│       │   ├── ScheduleParser.java                 ← OK
│       │   ├── BusArrival.java                     ← OK
│       │   └── StationData.java                    ← OK
│       ├── cache/StationCache.java                 ← OK
│       ├── render/ScheduleRenderer.java            ← OK
│       └── epd/
│           ├── EpdConnector.java                   ← MUST FIX (see §4)
│           └── BusScheduleService.java             ← Mostly OK, one tweak (see §4.3)
```

### 3.2 What works

- Foreground service with START_STICKY + BootReceiver auto-start
- 30-second refresh cycle
- 3-retry API client with 2s / 4s exponential backoff
- Offline fallback chain: JSON cache → PNG cache → placeholder render
- Splash screen ("TRANSPORT PUBLIC") on first EPD connect
- Reconnect logic without splash on subsequent binds
- Bind to EPD service succeeds (`onServiceConnected` fires reliably)

### 3.3 What's broken or wrong

1. **`EpdConnector` uses a hand-written stub of `com.geniatech.el133sdk.EpdManager`** with methods that don't match the real service AIDL. Specifically:
    - It declares `sendImageBitmap(Bitmap)` — **this method does not exist** on the real service. The real method is `sendImage(Bitmap)`.
    - It declares `getEPDInfo()` as returning `String` — the real method returns `void` and delivers the result via broadcast (§2.3).
2. **`EpdConnector.sendBitmap` calls `epdManager.sendImageBitmap(bitmap)`** — must be `sendImage(bitmap)`.
3. **`EpdConnector.getEpdInfo()` returns the (always null) result of `epdManager.getEPDInfo()`** — must be deleted or rewritten with the broadcast pattern. The user has been calling this from `BusScheduleService` and interpreting `null` as failure; **`null` was a false negative** caused by the void-vs-String mismatch, not an actual problem with the bind.

---

## 4. The fix — exact changes to make

Execute these steps in order. After each step, briefly verify the result before proceeding to the next.

### 4.1 Locate and add the SDK AIDL file

The Geniatech SDK package includes a file at `aidl/com/geniatech/el133sdk/EpdManager.aidl`. The user has the SDK folder available. Ask them to share the contents of that AIDL file before proceeding if you can't access it directly.

Create the directory structure if it doesn't exist:
```
app/src/main/aidl/com/geniatech/el133sdk/
```

Place the SDK's `EpdManager.aidl` there verbatim. Do not edit it.

Verify the AIDL package declaration matches: it must say `package com.geniatech.el133sdk;` and declare `interface EpdManager`.

### 4.2 Delete the hand-written stub

Search the project for any file declaring `package com.geniatech.el133sdk` and a class or interface named `EpdManager` written by hand (not generated). It will likely be at:

```
app/src/main/java/com/geniatech/el133sdk/EpdManager.java
```

Or possibly elsewhere if the user put it under their own package and aliased the import. Verify by searching for `class EpdManager` and `interface EpdManager` across the whole project. Delete the hand-written one. The Gradle Android plugin will auto-generate the correct stub from the AIDL into `build/generated/aidl_source_output_dir/...` on the next build.

If `EpdManager` is referenced from a JAR or AAR in `app/libs/`, do not delete the JAR — but verify what it contains via `unzip -l app/libs/<file>.jar`. If it ships a `com/geniatech/el133sdk/EpdManager.class`, that's the hand-written one and the AAR/JAR needs to be removed or replaced with the Geniatech SDK's official library.

### 4.3 Update `EpdConnector.java`

Apply two edits:

**Edit A** — in `sendBitmap()`, replace the method call:

```java
// BEFORE
int result = epdManager.sendImageBitmap(bitmap);

// AFTER
int result = epdManager.sendImage(bitmap);
```

The success condition is `result == 0`. The previous code used `result >= 0` which happens to be correct (since 0 ≥ 0) but is misleading. Change the comparison for clarity:

```java
if (result == 0) {
    Log.i(TAG, "sendImage accepted by EPD service");
    return true;
} else {
    Log.e(TAG, "sendImage returned non-zero: " + result);
    return false;
}
```

**Edit B** — replace `getEpdInfo()` with the broadcast pattern, OR delete it if the user agrees it's not needed. Default to deletion unless the user indicates they want the panel info on screen. The current implementation is unsalvageable as written (returns null always because of the void/String mismatch in the SDK contract).

If keeping it:
- Add a `BroadcastReceiver` in `EpdConnector` for action `android.epdservice.action.RSP_GETDEVICEINFO`
- Register it in `bind()` after `bindService` succeeds
- Unregister it in `unbind()`
- Pull `panelName`, `panelW`, `panelH`, `firmware`, `vcom`, `temperature` from the intent extras (§2.3 has the exact extra keys)
- Add a getter that returns the most-recently-received info, or expose a `Listener` callback the service can subscribe to
- Call `epdManager.getEPDInfo()` (now void) inside `onServiceConnected` to trigger the initial query

Pick whichever is cleaner. Don't over-engineer.

### 4.4 Update `BusScheduleService.java`

Find the line:
```java
Log.i(TAG, "EPD connected — info: " + epd.getEpdInfo() + " configured: " + w + "x" + h);
```

If `getEpdInfo` was deleted in §4.3 Edit B, remove the `epd.getEpdInfo()` reference. Otherwise, update to reflect that info arrives async (don't try to read it synchronously on connect — it won't be there yet). A safe replacement that doesn't depend on async data:

```java
Log.i(TAG, "EPD connected — configured render size: " + w + "x" + h);
```

No other changes needed in `BusScheduleService`. The rest is correct.

### 4.5 Build, install, verify

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb logcat | grep -E "EpdConnector|EpdManager|BusSchedule|epdService"
```

**Expected log sequence within ~5 seconds of app start:**

```
EpdConnector  Binding to EPD service (direct ComponentName)
EpdConnector  Direct bind returned true — waiting for onServiceConnected
EpdConnector  EpdManager service connected — triggering immediate frame
BusScheduleService  refreshTask fired — fetching stationId=4 epd.isReady=true
BusScheduleService  Fetching API for stationId=4
BusScheduleService  EPD connected — configured render size: 2560x1440
EpdConnector  sendImage accepted by EPD service
```

**The EPD panel should update within a few seconds of `sendImage accepted by EPD service`.** If the panel updates, the integration works.

### 4.6 If sendImage fails

If `result != 0` consistently:

1. Check `adb shell getprop com.geniatech.epcmodel` returns `EPC3566`
2. Verify the EPD board is physically connected (ribbon + power)
3. Look for a more specific error in `adb logcat | grep -i epdservice`
4. Try the file-path variant `epdManager.sendImage("/sdcard/test.png")` with a pre-saved 2560×1440 PNG — if file path works but Bitmap doesn't, there may be a memory/Parcel size issue with a 2560×1440 Bitmap over Binder
5. If the file-path variant also fails, the panel model EPC3566 may not be supported by this SDK build — see §1.4 caveat. Tell the user.

---

## 5. Code style / conventions

- **Language**: Java, not Kotlin. Don't introduce Kotlin files.
- **Package root**: `ro.smarttrans.busschedule`
- **EPD code**: lives under `ro.smarttrans.busschedule.epd`
- **Logging**: use `Log.d/i/w/e` with a class-name `TAG` constant. Follow `EpdConnector`'s existing patterns.
- **Threading**: `Handler(Looper.getMainLooper())` for retries; foreground service callbacks on main thread; network I/O on `ExecutorService` (see `ApiClient`).
- **Constants**: use `private static final` with all-caps names.
- **Don't introduce new dependencies** without asking. The project uses plain `okhttp3` (or `HttpURLConnection`, verify) and standard AndroidX. No Retrofit, no Dagger, no Room.

---

## 6. Useful ADB commands for this project

```bash
# Verify EPD model identifier
adb shell getprop com.geniatech.epcmodel

# Confirm the EPD service is alive
adb shell dumpsys activity services com.geniatech.epc.core

# Verify SELinux mode (permissive on this dev board)
adb shell getenforce

# Watch app + EPD service log activity in real time
adb logcat -c && adb logcat | grep -E "EpdConnector|EpdManager|BusSchedule|epdService"

# List all Geniatech services
adb shell dumpsys package com.geniatech.epc.core | grep -A2 -i service

# Test EPD info broadcast manually (after app registers receiver)
adb shell am broadcast -a android.epdservice.action.RSP_GETDEVICEINFO \
  --es panelName EPC3566 --ei panelW 2560 --ei panelH 1440

# Pull system APKs for inspection (if needed)
adb shell ls /system_ext/priv-app/
adb pull /system_ext/priv-app/com.geniatech.epc.core/
```

---

## 7. Diagnostic notes — false signals to avoid

Past debugging hit several dead ends that wasted time. Recognize these so you don't repeat them:

1. **"sendImage returns 0 → failure"** — false. `0 == success`. See §2.2.
2. **"getEPDInfo returns null → AIDL mismatch / bind broken"** — false. The method is `void`; the result is broadcast. See §2.3.
3. **"`/dev/ebc` and `/sys/class/epd` are empty → kernel driver missing → need different firmware"** — false. The EPD is not a kernel-level device on this architecture. See §1.2.
4. **"`vendor.hwc.device.primary=HDMI-A` → display routing is wrong"** — false. HDMI is the only Android-side display; EPD is reached only through the SDK. See §1.2.
5. **"4-wire power connector reads 0V → EPD board has no power"** — unresolved hardware question, but **not blocking software work**. The user can test software-side completion without it. Flag it if `sendImage` keeps succeeding but no panel update is visible.
6. **`E CompositionEngine: Invalid device requested composition type change`** — cosmetic, not relevant. SurfaceFlinger spam on this firmware. Ignore.
7. **SELinux denials (`avc: denied ...`)** — `permissive` mode on dev board, so denials are logged but not enforced. Ignore unless we move to production firmware.

---

## 8. What is NOT in scope

Do not propose or implement any of these without explicit user instruction:

- Modifying SurfaceFlinger, HWC, device tree, or kernel
- Switching the primary display from HDMI to EPD
- Reflashing firmware or running RKDevTool
- Modifying `RKDevInfoWriteTool` settings (`com.geniatech.epcmodel` is already set correctly)
- Signing the app with `systemrkkey.jks` (unless production deployment comes up)
- Rewriting `BusScheduleService` lifecycle, `ApiClient` retry logic, `ScheduleParser`, `ScheduleRenderer`, `StationCache`, `BootReceiver`, or `MainActivity` — these are confirmed working
- Adding dependencies, build flavors, product variants, Hilt/Dagger, Compose, etc.
- "Improvements" or refactors that aren't tied to a specific bug or §4 item

---

## 9. Open questions to ask the user before significant work

Before starting §4, confirm:

1. **Where is the Geniatech SDK folder on disk?** You need access to `aidl/com/geniatech/el133sdk/EpdManager.aidl` from it. If you cannot read it, ask the user to paste the file's contents.
2. **Does the user want `getEpdInfo()` preserved as a working broadcast-based getter, or deleted?** Default to deletion unless they say otherwise.
3. **Has the user confirmed the EPD board is physically connected with power?** The 4-wire connector measured 0V earlier. If unresolved, `sendImage` may return 0 but produce no visible result on the panel — clarify this is a hardware issue, not a code bug.

---

## 10. Quick reference — full bind + send template

For your reference, the canonical minimal integration looks like this (after §4 changes):

```java
// 1. Bind
Intent intent = new Intent();
intent.setComponent(new ComponentName(
    "com.geniatech.epc.core",
    "com.geniatech.el133sdk.epdService"
));
context.bindService(intent, conn, Context.BIND_AUTO_CREATE);

// 2. onServiceConnected
epdManager = EpdManager.Stub.asInterface(service);

// 3. Send a frame
try {
    int result = epdManager.sendImage(bitmap);
    if (result == 0) {
        // accepted; panel will refresh in ~1–4 seconds
    }
} catch (RemoteException e) {
    // service died — will reconnect via onServiceDisconnected
}

// 4. (Optional) get panel info
//    a) register BroadcastReceiver for "android.epdservice.action.RSP_GETDEVICEINFO"
//    b) call epdManager.getEPDInfo()   // void
//    c) read extras from the broadcast: panelName, panelW, panelH, firmware, vcom, temperature
```

That's the whole contract. Everything else in the SDK is sugar on top.

---

End of handoff document. Begin with §9 (clarifying questions if needed), then proceed through §4 in order.