package ro.smarttrans.busschedule.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ro.smarttrans.busschedule.BuildConfig;
import ro.smarttrans.busschedule.R;
import ro.smarttrans.busschedule.api.ApiClient;
import ro.smarttrans.busschedule.api.ScheduleParser;
import ro.smarttrans.busschedule.cache.StationCache;
import ro.smarttrans.busschedule.epd.EpdConnector;
import ro.smarttrans.busschedule.model.BusArrival;
import ro.smarttrans.busschedule.model.StationData;
import ro.smarttrans.busschedule.renderer.ScheduleRenderer;
import ro.smarttrans.busschedule.wifi.WifiConnector;

/**
 * Core foreground service that owns the entire bus-schedule display pipeline.
 *
 * <h2>Architecture overview</h2>
 * <pre>
 *   BootReceiver / launcher
 *       │
 *       └─▶ startForegroundService()
 *               │
 *               ▼
 *   BusScheduleService.onCreate()
 *       ├─ EpdConnector.bind()          — async AIDL bind to Geniatech EPD service
 *       ├─ WifiConnector.start()        — periodic WiFi-radio watchdog
 *       └─ registerNetworkCallback()    — ConnectivityManager listener
 *
 *   On EPD connected (onConnectedCallback):
 *       └─▶ splash bitmap → scheduleRefresh(4 000 ms)
 *
 *   refreshTask (main thread, periodic):
 *       └─▶ worker thread:
 *               ├─ ApiClient.fetchSchedule()   — HTTP GET with 3× retry
 *               ├─ ScheduleParser.parse()       — JSON → StationData
 *               ├─ ScheduleRenderer.render()    — StationData → Bitmap
 *               └─ sendFullRefresh() or sendSinglePartial()
 *                       └─▶ EpdConnector.sendBitmap() / sendPartialBitmap()
 * </pre>
 *
 * <h2>Single-patch constraint</h2>
 * The Geniatech EPC3566 EPD driver accepts at most ONE sendPartialBitmap call per
 * refresh cycle (see docs/epd-behavior.md §2). All changed zones are unioned into a
 * single bounding rect per cycle. If the bounding rect exceeds
 * {@link #MAX_PARTIAL_AREA_FRACTION} of the screen, a full refresh is sent instead.
 *
 * <h2>Anti-ghosting</h2>
 * E-paper panels accumulate ghost images when partial updates run for too long.
 * A full refresh is forced every {@link #MAX_CONSECUTIVE_PARTIALS} partial cycles
 * and at least every {@link #FORCED_FULL_REFRESH_INTERVAL_MS} ms regardless of count.
 *
 * <h2>Thread model</h2>
 * <ul>
 *   <li>{@code mainHandler} / main thread — schedules {@code refreshTask}, runs network
 *       callbacks, handles {@code onStartCommand}</li>
 *   <li>{@code worker} (single-thread executor) — all blocking I/O, bitmap rendering,
 *       and EPD calls</li>
 * </ul>
 * Fields read/written from both threads are marked {@code volatile}.
 */
public class BusScheduleService extends Service {

    private static final String TAG      = "BusScheduleService";
    private static final String CHANNEL  = "bus_schedule";
    private static final int    NOTIF_ID = 1;

    /**
     * Send this action via {@code startForegroundService} to change the active station
     * at runtime without restarting the service.
     * Extra: {@link #EXTRA_STATION_ID} (int) — new station id.
     */
    public static final String ACTION_SET_STATION = "ro.smarttrans.busschedule.SET_STATION";
    public static final String EXTRA_STATION_ID   = "stationId";

    // Main-thread handler drives the refresh schedule; actual work runs on `worker`.
    private final Handler         mainHandler     = new Handler(Looper.getMainLooper());
    private final ExecutorService worker          = Executors.newSingleThreadExecutor();
    private final AtomicBoolean   fetchInProgress = new AtomicBoolean(false);

    private ApiClient        apiClient;
    private ScheduleParser   parser;
    private StationCache     cache;
    private EpdConnector     epd;
    private ScheduleRenderer renderer;
    private WifiConnector    wifiConnector;
    private ConnectivityManager.NetworkCallback networkCallback;

    // ── Partial-refresh policy ─────────────────────────────────────────────────

    /**
     * Maximum fraction of screen area that a partial-refresh bounding rect may cover
     * before the service falls back to a full refresh. 0.60 = 60 %.
     * Rationale: large partial updates cost nearly as much as a full refresh on this panel
     * but accumulate ghosting without the clean slate that full mode provides.
     */
    private static final float MAX_PARTIAL_AREA_FRACTION       = 0.60f;

    /**
     * Number of consecutive partial refreshes after which a forced full refresh is
     * triggered regardless of how much data changed. Prevents ghost-image buildup.
     */
    private static final int   MAX_CONSECUTIVE_PARTIALS        = 30;

    /**
     * Wall-clock interval (ms) after which a full refresh is forced even if the
     * consecutive-partial counter hasn't been reached. 15 minutes.
     */
    private static final long  FORCED_FULL_REFRESH_INTERVAL_MS = 15 * 60 * 1_000L;

    /** Left-edge x-fraction for the bus-destination column. Used to bound partial updates. */
    private static final float Z_DEST_X1 = 0.20f;

    // ── Mutable service state ──────────────────────────────────────────────────

    /**
     * Active station id. Written on the main thread ({@code onStartCommand}) and read
     * on the worker thread ({@code refreshTask}) — must be volatile.
     */
    private volatile int  stationId = BuildConfig.STATION_ID;

    /** Refresh interval driven by the server's {@code pdel} hint (minimum 10 s). */
    private int  refreshMs    = BuildConfig.DEFAULT_REFRESH_SECONDS * 1000;

    /** Epoch-ms of the last successful API response; 0 if no success yet. */
    private long lastSuccessMs = 0;

    /**
     * True when the last API fetch succeeded. Written on the worker thread and read
     * in the ConnectivityManager callback on the main thread — must be volatile.
     */
    private volatile boolean online = false;

    /** Guards the splash sequence: the first EPD connection shows a splash; reconnects skip it. */
    private volatile boolean splashShown = false;

    /**
     * The {@link StationData} that was used to produce the last bitmap sent to the EPD.
     * Null on first run and after a station change. The worker thread diffs the incoming
     * data against this snapshot to decide which zones need a partial update.
     * Written and read exclusively on the worker thread; volatile only because
     * {@code onStartCommand} nulls it on the main thread when the station changes.
     */
    private volatile StationData lastStation = null;

    // ── Anti-ghosting state (worker thread only) ───────────────────────────────

    /** Number of partial refreshes sent since the last full refresh. */
    private int  consecutivePartialsCount = 0;

    /** Epoch-ms when the last full refresh was sent; 0 before the first full refresh. */
    private long lastFullRefreshMs        = 0L;

    /**
     * Minute-of-day (0–1439, Europe/Bucharest) when the clock was last included in a
     * rendered frame. -1 means "never rendered" — clock zone is always included on the
     * first partial cycle after service start.
     */
    private int lastRenderedClockMinute = -1;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Initialises all sub-components, binds the EPD service, and elevates the process
     * to a foreground service. The EPD bind is asynchronous — actual rendering begins
     * inside {@code epd.setOnConnectedCallback}.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate — display=" + BuildConfig.DISPLAY_WIDTH + "x" + BuildConfig.DISPLAY_HEIGHT
                + " station=" + stationId + " refresh=" + BuildConfig.DEFAULT_REFRESH_SECONDS + "s");

        apiClient     = new ApiClient();
        parser        = new ScheduleParser();
        cache         = new StationCache(this);
        epd           = new EpdConnector(this);
        renderer      = new ScheduleRenderer(BuildConfig.DISPLAY_WIDTH, BuildConfig.DISPLAY_HEIGHT);
        wifiConnector = new WifiConnector(this, epd);
        wifiConnector.start();

        // When EPD connects (async): first time show splash for 4 s, then start normal refresh.
        // On subsequent reconnects go straight to refresh so cached data is restored quickly.
        epd.setOnConnectedCallback(() -> {
            Log.i(TAG, "EPD connected — configured render size: "
                    + BuildConfig.DISPLAY_WIDTH + "x" + BuildConfig.DISPLAY_HEIGHT);
            // getEpdInfo() logs panel name/size/firmware internally; the JSON may be null for EPC3566
            String info = epd.getEpdInfo();
            if (info != null) {
                try {
                    org.json.JSONObject j = new org.json.JSONObject(info);
                    int pw = j.optInt("panelW", -1);
                    int ph = j.optInt("panelH", -1);
                    if (pw > 0 && ph > 0
                            && (pw != BuildConfig.DISPLAY_WIDTH || ph != BuildConfig.DISPLAY_HEIGHT)) {
                        Log.w(TAG, "DIMENSION MISMATCH: panel reports " + pw + "x" + ph
                                + " but gradle.properties has " + BuildConfig.DISPLAY_WIDTH
                                + "x" + BuildConfig.DISPLAY_HEIGHT
                                + " — update display_width/display_height in gradle.properties");
                    }
                } catch (Exception ignored) {}
            }
            // Disable panel-level auto-refresh so it doesn't race with our software refreshes.
            epd.disableAutoRefresh();
            if (!splashShown) {
                splashShown = true;
                worker.execute(() -> {
                    Log.i(TAG, "Sending splash bitmap");
                    Bitmap splash = renderer.renderSplash();
                    long t = System.currentTimeMillis();
                    boolean sent = epd.sendBitmap(splash);
                    Log.i(TAG, "[PERF] sendBitmap splash " + splash.getWidth() + "x" + splash.getHeight()
                            + " sent=" + sent + " ms=" + (System.currentTimeMillis() - t));
                    scheduleRefresh(4_000);
                });
            } else {
                scheduleRefresh(0);
            }
        });

        epd.bind();
        registerNetworkCallback();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Initializing…"));
        Log.i(TAG, "onCreate complete — EPD bind requested, waiting for connection");
    }

    /**
     * Handles {@link #ACTION_SET_STATION} to swap the active station at runtime.
     * All other intents are ignored (START_STICKY re-delivers null on restart).
     * An immediate refresh is triggered only when the splash/connect sequence is
     * already complete, to avoid racing with the first-frame bitmap.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SET_STATION.equals(intent.getAction())) {
            int newId = intent.getIntExtra(EXTRA_STATION_ID, stationId);
            Log.i(TAG, "onStartCommand ACTION_SET_STATION newId=" + newId + " (was " + stationId + ")");
            if (newId != stationId) {
                stationId = newId;
                lastSuccessMs = 0;
                lastStation = null; // Edge case 7: different station → force full refresh on next cycle
                Log.i(TAG, "Station change queued — new station " + newId + " takes effect on next cycle");
            }
        } else {
            Log.i(TAG, "onStartCommand — stationId=" + stationId + " splashShown=" + splashShown);
        }
        // Only trigger an immediate refresh if the splash/connect sequence is already complete.
        // Otherwise onConnectedCallback owns the first frame — scheduling here would race with the splash.
        if (splashShown) {
            scheduleRefresh(0);
        }
        return START_STICKY; // OS restarts the service if killed
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(refreshTask);
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        epd.unbind();
        wifiConnector.stop();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (networkCallback != null && cm != null) cm.unregisterNetworkCallback(networkCallback);
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Core refresh loop ──────────────────────────────────────────────────────

    /**
     * Periodic task posted to the main-thread handler. Guards against overlapping
     * executions with {@link #fetchInProgress}. Delegates all blocking work to
     * {@link #worker}. Self-reschedules in the {@code finally} block regardless of
     * success or failure so the display is always eventually updated.
     *
     * <p>Decision tree for each cycle:
     * <ol>
     *   <li>Fetch JSON from API — on failure, show offline fallback and reschedule.</li>
     *   <li>Parse, render full bitmap.</li>
     *   <li>Determine if a forced full refresh is required (first run, size change,
     *       reshuffle, offline→online, anti-ghosting).</li>
     *   <li>If not forced: compute bounding rect of all changed zones; if the rect
     *       exceeds {@link #MAX_PARTIAL_AREA_FRACTION}, fall back to full refresh;
     *       otherwise send a single partial update.</li>
     *   <li>If nothing changed and minute hasn't rolled: skip EPD call entirely.</li>
     * </ol>
     */
    private final Runnable refreshTask = () -> {
        if (fetchInProgress.getAndSet(true)) {
            Log.d(TAG, "refreshTask skipped — fetch already in progress");
            return;
        }
        Log.d(TAG, "refreshTask fired — fetching stationId=" + stationId + " epd.isReady=" + epd.isReady());
        worker.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                // Log connectivity state so we know if Android thinks we're online
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                Network activeNet = cm != null ? cm.getActiveNetwork() : null;
                NetworkCapabilities caps = (cm != null && activeNet != null)
                        ? cm.getNetworkCapabilities(activeNet) : null;
                boolean hasInternet = caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                Log.d(TAG, "Network check — activeNetwork=" + activeNet
                        + " hasValidatedInternet=" + hasInternet
                        + " internalOnlineFlag=" + online);

                Log.d(TAG, "Fetching API for stationId=" + stationId);
                String json = apiClient.fetchSchedule(stationId);
                Log.d(TAG, "API response received, length=" + json.length() + " chars"
                        + " — preview=" + json.substring(0, Math.min(300, json.length())));

                StationData station;
                try {
                    station = parser.parse(json);
                } catch (Exception parseEx) {
                    Log.e(TAG, "JSON parse failed: " + parseEx.getClass().getSimpleName()
                            + ": " + parseEx.getMessage()
                            + " — raw JSON was: " + json.substring(0, Math.min(500, json.length())), parseEx);
                    throw parseEx;
                }
                Log.i(TAG, "Parsed station: name='" + station.name + "' arrivals=" + station.arrivals.size()
                        + " refreshInterval=" + station.refreshIntervalSeconds + "s");

                refreshMs = Math.max(station.refreshIntervalSeconds, 10) * 1000;
                lastSuccessMs = System.currentTimeMillis();

                boolean transitionedOnline = !online; // Edge case 6: was offline, now online
                online = true;
                cache.saveJson(stationId, json);

                long renderStart = System.currentTimeMillis();
                Bitmap bmp = renderer.render(station, false, lastSuccessMs);
                Log.i(TAG, "[PERF] render ms=" + (System.currentTimeMillis() - renderStart)
                        + " size=" + bmp.getWidth() + "x" + bmp.getHeight());

                // Include the header clock zone if the displayed minute has changed since last render
                int currentMinute = currentClockMinute();
                boolean minuteRolled = (lastRenderedClockMinute != currentMinute);

                // Determine if a full refresh is forced by policy (checked before area)
                String forceReason = null;
                if (lastStation == null) {
                    forceReason = "first-run";                       // Edge case 1
                } else if (lastStation.arrivals.size() != station.arrivals.size()) {
                    forceReason = "list-size-changed";               // Edge case 2
                } else if (isListReshuffled(lastStation, station)) {
                    forceReason = "list-reshuffled";                 // Edge case 3
                } else if (transitionedOnline) {
                    forceReason = "offline-to-online";               // Edge case 6
                } else if (shouldDoFullRefresh()) {
                    forceReason = "anti-ghosting";                   // Step 3
                }

                if (forceReason != null) {
                    Log.d(TAG, "Full refresh — reason=" + forceReason);
                    sendFullRefresh(bmp);
                } else {
                    Rect bounds = computeBoundingRect(
                            lastStation, station, minuteRolled, bmp.getWidth());

                    if (bounds == null) {
                        // No row changes and minute hasn't rolled — nothing to update
                        Log.d(TAG, "No changes detected — skipping EPD update this cycle");
                    } else {
                        float patchFraction = (float)(bounds.width() * bounds.height())
                                / (bmp.getWidth() * bmp.getHeight());
                        if (patchFraction > MAX_PARTIAL_AREA_FRACTION) {
                            Log.d(TAG, "Bounding rect covers "
                                    + String.format("%.0f%%", patchFraction * 100)
                                    + " of screen — switching to full refresh");
                            sendFullRefresh(bmp);
                        } else {
                            sendSinglePartial(bmp, bounds);
                        }
                    }
                }

                lastRenderedClockMinute = currentMinute;
                lastStation = station;
                cache.saveBitmap(stationId, bmp);
                notify("● LIVE – " + station.name);
                Log.d(TAG, "Refresh complete — next in " + refreshMs + "ms"
                        + "  total=" + (System.currentTimeMillis() - t0) + "ms");

            } catch (IOException e) {
                Log.w(TAG, "Fetch failed: " + e.getMessage() + " — showing cached data");
                online = false;
                showOfflineFallback();
                notify("⚠ Offline – cached data shown");
                Log.i(TAG, "Triggering WiFi reconnect after fetch failure");
                wifiConnector.ensureConnected();
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error in refreshTask", e);
            } finally {
                fetchInProgress.set(false);
                scheduleRefresh(refreshMs); // queue next cycle
            }
        });
    };

    /**
     * Cancels any pending refresh and posts a new one after {@code delayMs} ms.
     * Always called on the main thread.
     */
    private void scheduleRefresh(int delayMs) {
        mainHandler.removeCallbacks(refreshTask);
        mainHandler.postDelayed(refreshTask, delayMs);
    }

    // ── Full / partial send ────────────────────────────────────────────────────

    /**
     * Sends {@code bmp} as a full-screen refresh and resets the anti-ghosting counters.
     * Called from the worker thread.
     */
    private void sendFullRefresh(Bitmap bmp) {
        long t = System.currentTimeMillis();
        boolean sent = epd.sendBitmap(bmp);
        Log.i(TAG, "[PERF] sendBitmap full " + bmp.getWidth() + "x" + bmp.getHeight()
                + " sent=" + sent + " ms=" + (System.currentTimeMillis() - t));
        consecutivePartialsCount = 0;
        lastFullRefreshMs = System.currentTimeMillis();
    }

    /**
     * Crops {@code bounds} from {@code bmp}, aligns to 8-pixel grid, and sends exactly one
     * partial update. Safe against Bitmap pixel-buffer sharing: does not recycle the patch
     * if its allocation byte count equals the source's (shared buffer). Edge case 8.
     */
    private void sendSinglePartial(Bitmap bmp, Rect bounds) {
        Rect aligned = alignToEightPixels(bounds, bmp.getWidth());
        Bitmap patch = Bitmap.createBitmap(bmp, aligned.left, aligned.top,
                aligned.width(), aligned.height());
        boolean ownAllocation = patch.getAllocationByteCount() != bmp.getAllocationByteCount();
        long t = System.currentTimeMillis();
        boolean sent;
        try {
            sent = epd.sendPartialBitmap(patch, aligned.left, aligned.top);
        } finally {
            if (ownAllocation) patch.recycle();
        }
        Log.i(TAG, "[PERF] sendPartialBitmap x=" + aligned.left + " y=" + aligned.top
                + " size=" + aligned.width() + "x" + aligned.height()
                + " sent=" + sent + " ms=" + (System.currentTimeMillis() - t));
        consecutivePartialsCount++;
    }

    // ── Partial refresh helpers ────────────────────────────────────────────────

    /**
     * Returns the single bounding {@link Rect} covering every changed zone, or null if
     * nothing needs updating. Includes the header clock zone when the minute has rolled.
     * Edge case 5: empty arrivals list still produces a clock-zone rect on minute roll.
     */
    private Rect computeBoundingRect(StationData oldSt, StationData newSt,
                                     boolean includeClockZone, int W) {
        Rect bounds = null;

        // Clock zone: rightmost 13% of header row
        if (includeClockZone) {
            bounds = new Rect(W - (int)(W * 0.13f), 0, W, renderer.headerH());
        }

        int n = newSt.arrivals.size();
        for (int i = 0; i < n; i++) {
            BusArrival o  = oldSt.arrivals.get(i);
            BusArrival nu = newSt.arrivals.get(i);
            if (!rowChanged(o, nu)) continue;

            int rTop = renderer.rowTop(i, n);
            int rH   = renderer.rowH(n);
            int x1   = W; // expanded left depending on which field changed

            if (!Objects.equals(o.shortName,     nu.shortName))  x1 = 0;
            if (!Objects.equals(o.toStopName,    nu.toStopName))  x1 = Math.min(x1, (int)(W * Z_DEST_X1));
            if (!Objects.equals(o.displayedTime, nu.displayedTime)
                    || o.realtime != nu.realtime
                    || o.displayedTimeColor != nu.displayedTimeColor) {
                x1 = Math.min(x1, W - (int)(W * 0.115f));
            }

            if (x1 < W) {
                Rect row = new Rect(x1, rTop, W, rTop + rH);
                if (bounds == null) bounds = row;
                else bounds.union(row);
            }
        }

        return bounds; // null → nothing changed
    }

    /** Snaps rect left edge down and right edge up to multiples of 8. Edge case 9. */
    private static Rect alignToEightPixels(Rect r, int maxWidth) {
        int x     = (r.left / 8) * 8;
        int right = Math.min(maxWidth, ((r.right + 7) / 8) * 8);
        return new Rect(x, r.top, right, r.bottom);
    }

    /** Returns true when anti-ghosting policy requires a forced full refresh this cycle. */
    private boolean shouldDoFullRefresh() {
        if (consecutivePartialsCount >= MAX_CONSECUTIVE_PARTIALS) return true;
        long now = System.currentTimeMillis();
        return lastFullRefreshMs > 0 && now - lastFullRefreshMs >= FORCED_FULL_REFRESH_INTERVAL_MS;
    }

    /**
     * Returns true if more than half the rows have a different line (shortName) at the same
     * index — the signature of the list shifting up after a bus departs. Edge case 3.
     */
    private static boolean isListReshuffled(StationData oldSt, StationData newSt) {
        int n = newSt.arrivals.size();
        if (n == 0) return false;
        int mismatches = 0;
        for (int i = 0; i < n; i++) {
            if (!Objects.equals(oldSt.arrivals.get(i).shortName,
                                newSt.arrivals.get(i).shortName)) mismatches++;
        }
        return mismatches > n / 2;
    }

    /** Edge case 4: null-safe field comparison. */
    private static boolean rowChanged(BusArrival a, BusArrival b) {
        return !Objects.equals(a.shortName,     b.shortName)
                || !Objects.equals(a.toStopName,    b.toStopName)
                || !Objects.equals(a.displayedTime, b.displayedTime)
                || a.realtime != b.realtime
                || a.displayedTimeColor != b.displayedTimeColor;
    }

    /** Returns the current minute-of-day (0–1439) in the display timezone. */
    private static int currentClockMinute() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Bucharest"));
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    // ── Offline fallback ───────────────────────────────────────────────────────

    private void showOfflineFallback() {
        Log.w(TAG, "showOfflineFallback — checking cache for stationId=" + stationId);
        // Prefer re-rendering from cached JSON so the OFFLINE indicator is shown
        String json = cache.loadJson(stationId);
        if (json != null) {
            Log.d(TAG, "Cached JSON found (" + json.length() + " chars) — re-rendering with OFFLINE flag");
            try {
                StationData station = parser.parse(json);
                Bitmap bmp = renderer.render(station, true, cache.getLastUpdateTime(stationId));
                long t = System.currentTimeMillis();
                boolean sent = epd.sendBitmap(bmp);
                Log.i(TAG, "[PERF] sendBitmap offline-json " + bmp.getWidth() + "x" + bmp.getHeight()
                        + " sent=" + sent + " ms=" + (System.currentTimeMillis() - t));
                return;
            } catch (Exception e) {
                Log.e(TAG, "Offline re-render failed", e);
            }
        } else {
            Log.w(TAG, "No cached JSON — trying saved PNG bitmap");
        }
        // Last resort: push the previously saved PNG as-is, or render a placeholder
        Bitmap bmp = cache.loadBitmap(stationId);
        if (bmp != null) {
            long t = System.currentTimeMillis();
            boolean sent = epd.sendBitmap(bmp);
            Log.i(TAG, "[PERF] sendBitmap offline-png " + bmp.getWidth() + "x" + bmp.getHeight()
                    + " sent=" + sent + " ms=" + (System.currentTimeMillis() - t));
        } else {
            Log.w(TAG, "No cached bitmap — rendering offline placeholder");
            Bitmap placeholder = renderer.renderOfflinePlaceholder();
            cache.saveBitmap(stationId, placeholder);
            long t = System.currentTimeMillis();
            boolean sent = epd.sendBitmap(placeholder);
            Log.i(TAG, "[PERF] sendBitmap offline-placeholder "
                    + placeholder.getWidth() + "x" + placeholder.getHeight()
                    + " sent=" + sent + " ms=" + (System.currentTimeMillis() - t));
        }
    }

    // ── Network connectivity callback ──────────────────────────────────────────

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                if (!online) {
                    Log.i(TAG, "Network restored → triggering refresh");
                    scheduleRefresh(1_500); // short grace period for DNS
                }
            }
            @Override
            public void onLost(@NonNull Network network) {
                online = false;
            }
        };
        if (cm != null) {
            cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                networkCallback);
        }
    }

    // ── Notification helpers ───────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Bus Schedule Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Displays real-time bus arrivals on the EPD screen");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Bus Schedule")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_bus)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    @android.annotation.SuppressLint("NotificationPermission")
    private void notify(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
