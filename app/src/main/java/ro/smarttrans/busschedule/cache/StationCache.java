package ro.smarttrans.busschedule.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Disk cache for station data. Persists across service restarts so the EPD can show
 * the last-known timetable while the device is offline or the API is unreachable.
 *
 * <h2>Storage layout ({@code filesDir/cache/})</h2>
 * <pre>
 *   station_&lt;id&gt;.json   — raw API response string (UTF-8)
 *   station_&lt;id&gt;.png    — last rendered full-screen bitmap (lossless PNG)
 * </pre>
 *
 * <h2>Offline strategy</h2>
 * {@code BusScheduleService.showOfflineFallback()} prefers re-parsing the cached JSON
 * (so the OFFLINE indicator is rendered) and falls back to the saved PNG if JSON is
 * absent, then to a blank placeholder as a last resort.
 */
public class StationCache {
    private static final String TAG = "StationCache";

    private final File cacheDir;

    public StationCache(Context context) {
        cacheDir = new File(context.getFilesDir(), "cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    /** Persists the raw API JSON string for {@code stationId}. */
    public void saveJson(int stationId, String json) {
        write(jsonFile(stationId), json.getBytes(Charset.forName("UTF-8")));
    }

    /**
     * Loads the cached JSON for {@code stationId}.
     *
     * @return UTF-8 decoded string, or {@code null} if no cache file exists or the
     *         read fails
     */
    public String loadJson(int stationId) {
        byte[] data = read(jsonFile(stationId));
        return data != null ? new String(data, Charset.forName("UTF-8")) : null;
    }

    /** Persists {@code bitmap} as a lossless PNG for {@code stationId}. */
    public void saveBitmap(int stationId, Bitmap bitmap) {
        File f = bitmapFile(stationId);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (IOException e) {
            Log.e(TAG, "saveBitmap failed stationId=" + stationId, e);
        }
    }

    /**
     * Loads the cached PNG for {@code stationId}.
     *
     * @return decoded {@link Bitmap}, or {@code null} if no file exists
     */
    public Bitmap loadBitmap(int stationId) {
        File f = bitmapFile(stationId);
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    /** Returns the last-modified timestamp (ms) of the cached JSON, or 0 if absent. */
    public long getLastUpdateTime(int stationId) {
        return jsonFile(stationId).lastModified();
    }

    /** Returns {@code true} if a cached JSON file exists for {@code stationId}. */
    public boolean hasJson(int stationId) {
        return jsonFile(stationId).exists();
    }

    // ── internal ───────────────────────────────────────────────────────────────

    private File jsonFile(int stationId)   { return new File(cacheDir, "station_" + stationId + ".json"); }
    private File bitmapFile(int stationId) { return new File(cacheDir, "station_" + stationId + ".png");  }

    private void write(File f, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        } catch (IOException e) {
            Log.e(TAG, "write failed: " + f.getName(), e);
        }
    }

    /**
     * Reads the entire file into a byte array.
     *
     * <p>{@link FileInputStream#read(byte[])} is not guaranteed to fill the buffer in a
     * single call (the OS may return a partial read). This method loops until the buffer
     * is fully populated or EOF is reached, so callers always receive complete data.
     *
     * @return full file contents, or {@code null} if the file does not exist, could not
     *         be read, or an unexpected EOF was hit before the file was fully consumed
     */
    private byte[] read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) break; // unexpected EOF before buffer was filled
                off += n;
            }
            if (off != buf.length) {
                Log.w(TAG, "read short: expected " + buf.length + " bytes, got " + off
                        + " — discarding " + f.getName());
                return null;
            }
            return buf;
        } catch (IOException e) {
            Log.e(TAG, "read failed: " + f.getName(), e);
            return null;
        }
    }
}
