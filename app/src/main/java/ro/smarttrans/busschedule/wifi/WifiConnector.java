package ro.smarttrans.busschedule.wifi;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import ro.smarttrans.busschedule.epd.EpdConnector;

/**
 * Monitors the WiFi radio state and turns it back on if it is disabled.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Poll the WiFi radio state every {@link #CHECK_INTERVAL_MS} ms.</li>
 *   <li>If the radio is off, enable it via the Geniatech SDK ({@code setWifiOn()}) or
 *       fall back to {@code WifiManager.setWifiEnabled(true)} if the EPD service is not
 *       yet bound.</li>
 *   <li>After enabling, re-check after {@link #RETRY_AFTER_ENABLE_MS} ms to confirm
 *       the radio came up.</li>
 * </ul>
 *
 * <h2>Network selection</h2>
 * This class only controls the radio. SSID selection and authentication are handled by
 * Android's WiFi framework — the SMARTTRANS network must already be saved on the device
 * (done once via {@code adb shell cmd wifi connect-network "SMARTTRANS" wpa2 "SmartTrans2021"}).
 *
 * <h2>Retry guard</h2>
 * The retry callback is stored in the named field {@link #retryEnableCheck} so that
 * {@code handler.removeCallbacks(retryEnableCheck)} can reliably cancel any in-flight
 * retry before scheduling a new one. This prevents unbounded callback accumulation when
 * {@link #ensureConnected()} is called repeatedly while WiFi is persistently unavailable.
 */
public class WifiConnector {

    private static final String TAG = "WifiConnector";

    /** How often the periodic watchdog polls the WiFi radio state. */
    private static final int CHECK_INTERVAL_MS = 30_000;

    /** How long to wait after calling setWifiEnabled before re-checking the radio state. */
    private static final int RETRY_AFTER_ENABLE_MS = 5_000;

    private final WifiManager  wifiManager;
    private final EpdConnector epdConnector;
    private final Handler      handler = new Handler(Looper.getMainLooper());
    private boolean running = false;

    /**
     * Named Runnable for the post-enable re-check. Stored as a field so
     * {@code handler.removeCallbacks(retryEnableCheck)} can cancel an in-flight retry
     * before scheduling a replacement — prevents callback accumulation.
     */
    private final Runnable retryEnableCheck = this::checkAndEnableWifi;

    /** Periodic watchdog that fires every {@link #CHECK_INTERVAL_MS} ms while running. */
    private final Runnable periodicCheck = new Runnable() {
        @Override public void run() {
            if (!running) return;
            checkAndEnableWifi();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    public WifiConnector(Context context, EpdConnector epdConnector) {
        this.wifiManager  = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        this.epdConnector = epdConnector;
    }

    /** Starts the periodic WiFi watchdog. Safe to call multiple times — idempotent. */
    public void start() {
        running = true;
        Log.i(TAG, "WifiConnector started");
        handler.post(periodicCheck);
    }

    /** Stops the watchdog and cancels all pending callbacks. */
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "WifiConnector stopped");
    }

    /**
     * Triggers an immediate WiFi-state check from any thread.
     * Called by {@code BusScheduleService} after a failed API fetch to speed up
     * reconnection rather than waiting for the next periodic check.
     */
    public void ensureConnected() {
        handler.post(retryEnableCheck);
    }

    /**
     * Checks the WiFi radio state and enables it if off. Schedules a single
     * follow-up check via {@link #retryEnableCheck}; any previously scheduled
     * retry is cancelled first to prevent accumulation.
     */
    private void checkAndEnableWifi() {
        if (wifiManager == null) return;
        int state = wifiManager.getWifiState();
        if (state == WifiManager.WIFI_STATE_ENABLING || state == WifiManager.WIFI_STATE_ENABLED) {
            return; // radio already on or coming up — Android handles association
        }
        Log.w(TAG, "WiFi state=" + wifiStateStr(state) + " — enabling via Geniatech SDK");
        boolean dispatched = epdConnector.enableWifi();
        if (!dispatched) {
            // EPD service not yet connected — fall back to standard Android API
            boolean ok = wifiManager.setWifiEnabled(true);
            Log.w(TAG, "Fallback WifiManager.setWifiEnabled(true)=" + ok);
        }
        // Cancel any pending retry before scheduling a new one (prevents accumulation)
        handler.removeCallbacks(retryEnableCheck);
        handler.postDelayed(retryEnableCheck, RETRY_AFTER_ENABLE_MS);
    }

    private static String wifiStateStr(int s) {
        switch (s) {
            case WifiManager.WIFI_STATE_DISABLED:  return "DISABLED";
            case WifiManager.WIFI_STATE_DISABLING: return "DISABLING";
            case WifiManager.WIFI_STATE_ENABLED:   return "ENABLED";
            case WifiManager.WIFI_STATE_ENABLING:  return "ENABLING";
            default:                               return String.valueOf(s);
        }
    }
}
