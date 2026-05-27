package ro.smarttrans.busschedule.epd;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.geniatech.el133sdk.EpdManager;

/**
 * Thin wrapper around the Geniatech EPC3566 EPD Binder service.
 *
 * <h2>Binding</h2>
 * The service is bound by explicit {@link ComponentName} — implicit-action intents do not
 * match this package. If the primary component ({@code com.geniatech.epc.core /
 * com.geniatech.el133sdk.epdService}) is unavailable or protected, the class falls back
 * to {@code com.geniatech.epc.service / SendImageService}. On any disconnect the class
 * automatically rebinds after {@link #REBIND_DELAY_MS} ms.
 *
 * <h2>Async contract</h2>
 * {@code sendImageBitmap} and {@code sendpartImageBitmap} return immediately (the panel
 * refresh is asynchronous, taking 1–4 s). Callers must not send a second frame until the
 * first one has had time to complete — see {@code docs/epd-behavior.md §1}.
 *
 * <h2>One call per refresh cycle</h2>
 * {@code BusScheduleService} guarantees at most ONE send call per cycle.
 * Concurrent calls risk drop, queue, or coalesce depending on firmware behaviour
 * (see {@code docs/epd-behavior.md §2}).
 *
 * <h2>Return value</h2>
 * {@code 0} = request accepted by the EPD service; negative = error.
 * See {@code docs/epd-behavior.md §4} for known error codes.
 */
public class EpdConnector {
    private static final String TAG = "EpdConnector";
    private static final long REBIND_DELAY_MS = 3_000L;

    private final Context context;
    private EpdManager epdManager;
    private boolean bound = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable onConnectedCallback;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            epdManager = EpdManager.Stub.asInterface(service);
            bound = true;
            Log.i(TAG, "EpdManager service connected — triggering immediate frame");
            if (onConnectedCallback != null) {
                handler.post(onConnectedCallback);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            epdManager = null;
            bound = false;
            Log.w(TAG, "EpdManager service disconnected — rebinding in 3s");
            // Auto-rebind after a short delay to handle service restarts
            handler.postDelayed(EpdConnector.this::bind, REBIND_DELAY_MS);
        }
    };

    public EpdConnector(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Called on the main thread as soon as the EPD service connects (and after each reconnect). */
    public void setOnConnectedCallback(Runnable callback) {
        this.onConnectedCallback = callback;
    }

    public void bind() {
        // Primary: bind by explicit ComponentName — the service is registered by class, not by action.
        // "adb shell cmd package query-services -a com.geniatech.el133sdk.EpdManager" returns nothing,
        // confirming the old implicit-action intent never matched anything.
        Log.d(TAG, "Binding to EPD service (direct ComponentName)");
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.geniatech.epc.core",
                "com.geniatech.el133sdk.epdService"));
        try {
            boolean ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE);
            if (!ok) {
                // Direct bind failed — try the system app's SendImageService as fallback
                Log.w(TAG, "Direct bind returned false — trying SendImageService fallback");
                bindViaSendImageService();
            } else {
                Log.d(TAG, "Direct bind returned true — waiting for onServiceConnected");
            }
        } catch (SecurityException e) {
            // Service is restricted to platform-signed callers — try the public fallback
            Log.w(TAG, "Direct bind SecurityException — trying SendImageService fallback", e);
            bindViaSendImageService();
        } catch (Exception e) {
            Log.e(TAG, "Direct bind threw exception — retrying in 3s", e);
            handler.postDelayed(this::bind, REBIND_DELAY_MS);
        }
    }

    private void bindViaSendImageService() {
        Log.d(TAG, "Binding via com.geniatech.epc.service / SendImageService");
        Intent fallback = new Intent();
        fallback.setComponent(new ComponentName(
                "com.geniatech.epc.service",
                "com.geniatech.epc.service.server.SendImageService"));
        try {
            boolean ok = context.bindService(fallback, conn, Context.BIND_AUTO_CREATE);
            if (!ok) {
                Log.w(TAG, "SendImageService bind returned false — retrying direct bind in 3s");
                handler.postDelayed(this::bind, REBIND_DELAY_MS);
            } else {
                Log.d(TAG, "SendImageService bind returned true — waiting for onServiceConnected");
            }
        } catch (Exception e) {
            Log.e(TAG, "SendImageService bind threw exception — retrying in 3s", e);
            handler.postDelayed(this::bind, REBIND_DELAY_MS);
        }
    }

    public void unbind() {
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            try { context.unbindService(conn); } catch (Exception ignored) {}
            bound = false;
        }
    }

    /** Sends a bitmap to the EPD. Returns false if the service is not ready. */
    public boolean sendBitmap(Bitmap bitmap) {
        if (!bound || epdManager == null) {
            Log.w(TAG, "EPD not ready — frame dropped (bound=" + bound + ", manager=" + (epdManager != null ? "ok" : "null") + ")");
            return false;
        }
        Log.d(TAG, "Sending bitmap to EPD: " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " config=" + bitmap.getConfig());
        try {
            // Per SDK doc section 2.3: 0 = accepted by service. Negative = error.
            int result = epdManager.sendImageBitmap(bitmap);
            if (result == 0) {
                Log.i(TAG, "sendImageBitmap accepted by EPD service");
            } else {
                Log.e(TAG, "sendImageBitmap returned error code: " + result);
            }
            return result == 0;
        } catch (RemoteException e) {
            Log.e(TAG, "sendImageBitmap RemoteException", e);
            return false;
        }
    }

    /**
     * Sends a partial-region bitmap to the EPD at position (x, y).
     * The bitmap dimensions define the refresh region size.
     * Returns true if the service accepted the request (result == 0).
     */
    public boolean sendPartialBitmap(Bitmap bitmap, int x, int y) {
        if (!bound || epdManager == null) {
            Log.w(TAG, "sendPartialBitmap: EPD not ready");
            return false;
        }
        Log.d(TAG, "sendpartImageBitmap x=" + x + " y=" + y
                + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
        try {
            int result = epdManager.sendpartImageBitmap(bitmap, x, y);
            if (result != 0) Log.e(TAG, "sendpartImageBitmap returned error: " + result);
            return result == 0;
        } catch (RemoteException e) {
            Log.e(TAG, "sendpartImageBitmap RemoteException", e);
            return false;
        }
    }

    public boolean isReady() {
        return bound && epdManager != null;
    }

    /**
     * Sets the panel waveform mode (0–3). Must be called before the next sendImage/sendPartial.
     * Mode semantics are undocumented — see docs/epd-behavior.md for the empirical table.
     * Returns true if the call was dispatched, false if the EPD service is not yet connected.
     */
    public boolean setDisplayMode(int mode) {
        if (!bound || epdManager == null) {
            Log.w(TAG, "setDisplayMode: EPD not ready");
            return false;
        }
        try {
            epdManager.setDisplayMode(mode);
            Log.i(TAG, "setDisplayMode(" + mode + ") dispatched");
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "setDisplayMode RemoteException", e);
            return false;
        }
    }

    /**
     * Disables the panel's built-in periodic auto-refresh so it doesn't race with our
     * software-driven refreshes. Calls isOpenRefrushTime(false) — note doc/AIDL spelling.
     * Logs a warning and returns false if the firmware doesn't support it.
     */
    public boolean disableAutoRefresh() {
        if (!bound || epdManager == null) {
            Log.w(TAG, "disableAutoRefresh: EPD not ready");
            return false;
        }
        try {
            epdManager.isOpenRefrushTime(false);
            Log.i(TAG, "isOpenRefrushTime(false) dispatched — panel auto-refresh disabled");
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "isOpenRefrushTime not supported on this firmware", e);
            return false;
        }
    }

    /**
     * Turns WiFi on via the Geniatech system service (bypasses Android 10+ restriction
     * on WifiManager.setWifiEnabled for non-system apps).
     * Returns true if the call was dispatched, false if the EPD service is not yet connected.
     */
    public boolean enableWifi() {
        if (!bound || epdManager == null) {
            Log.w(TAG, "enableWifi: EPD service not connected yet — cannot call setWifiOn()");
            return false;
        }
        try {
            epdManager.setWifiOn();
            Log.i(TAG, "setWifiOn() dispatched via Geniatech SDK");
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "setWifiOn() RemoteException", e);
            return false;
        }
    }

    /**
     * Queries the EPD service for panel info and logs it.
     * Per SDK: getEPDInfo() returns a JSON string with panelName, panelW, panelH, firmware.
     * Returns the raw JSON, or null if not connected or service returns null.
     */
    public String getEpdInfo() {
        if (!bound || epdManager == null) return null;
        try {
            String json = epdManager.getEPDInfo();
            if (json != null) {
                try {
                    org.json.JSONObject j = new org.json.JSONObject(json);
                    Log.i(TAG, "EPD panel: name=" + j.optString("panelName", "?")
                            + " size=" + j.optInt("panelW", -1) + "x" + j.optInt("panelH", -1)
                            + " fw=" + j.optString("firmware", "?"));
                } catch (Exception parseEx) {
                    Log.w(TAG, "getEPDInfo raw (not JSON): " + json);
                }
            } else {
                Log.w(TAG, "getEPDInfo returned null — EPC3566 may not expose panel info");
            }
            return json;
        } catch (RemoteException e) {
            Log.e(TAG, "getEPDInfo RemoteException", e);
            return null;
        }
    }
}
