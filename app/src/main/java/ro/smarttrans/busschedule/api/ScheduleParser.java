package ro.smarttrans.busschedule.api;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ro.smarttrans.busschedule.BuildConfig;
import ro.smarttrans.busschedule.model.BusArrival;
import ro.smarttrans.busschedule.model.StationData;

/**
 * Parses the Smarttrans API JSON response into a {@link StationData} object.
 *
 * <h2>Expected API response structure</h2>
 * <pre>
 * [
 *   { "id": 0, "pdel": 30, ... },          // [0] config / metadata object
 *   {                                        // [1] station + arrivals object
 *     "id": 1,
 *     "name": "Statie XYZ",
 *     "data": {
 *       "rawElements": [
 *         {
 *           "shortName":          "11",      // bus line identifier
 *           "toStopName":         "Gara",    // destination
 *           "displayedTime":      "3 min",   // human-readable ETA
 *           "realtime":           1,         // 1 = GPS-tracked, 0 = scheduled
 *           "diffTime":           180,       // seconds until arrival (for sorting)
 *           "displayedTimeColor": 0          // colour hint (0 = default)
 *         },
 *         ...
 *       ]
 *     }
 *   }
 * ]
 * </pre>
 *
 * <h2>Robustness</h2>
 * All field lookups use {@code obj.has(key)} guards before access. Missing optional
 * fields fall back to safe defaults rather than throwing NPE. Unknown {@code id} values
 * are silently skipped so future API additions don't break the app.
 */
public class ScheduleParser {

    private static final String TAG = "ScheduleParser";

    /**
     * Parses a raw JSON string returned by the Smarttrans API.
     *
     * @param json raw API response — must be a JSON array at the root level
     * @return parsed {@link StationData} with arrivals sorted by {@code diffTime}
     *         and truncated to {@code BuildConfig.MAX_BUS_ROWS}
     * @throws com.google.gson.JsonSyntaxException if {@code json} is not valid JSON
     * @throws IllegalStateException if the root element is not a JSON array
     */
    public StationData parse(String json) {
        JsonArray root = JsonParser.parseString(json).getAsJsonArray();

        int refreshSeconds = BuildConfig.DEFAULT_REFRESH_SECONDS;
        String stationName = "";
        List<BusArrival> arrivals = new ArrayList<>();

        for (JsonElement el : root) {
            JsonObject obj = el.getAsJsonObject();

            // Guard: skip any element that lacks the "id" discriminator field
            if (!obj.has("id")) {
                Log.w(TAG, "API element missing 'id' field — skipping");
                continue;
            }
            int id = obj.get("id").getAsInt();

            if (id == 0) {
                // Config object: honour server-supplied refresh interval if sane (≥ 5 s)
                if (obj.has("pdel")) {
                    int pdel = obj.get("pdel").getAsInt();
                    if (pdel >= 5) refreshSeconds = pdel;
                }

            } else if (id == 1) {
                stationName = str(obj, "name");

                // Guard: "data" and "rawElements" may be absent if the station has no arrivals
                JsonObject dataObj = obj.has("data") ? obj.getAsJsonObject("data") : null;
                if (dataObj == null) {
                    Log.w(TAG, "Station object id=1 missing 'data' field — no arrivals");
                    continue;
                }
                JsonArray raw = dataObj.has("rawElements")
                        ? dataObj.getAsJsonArray("rawElements")
                        : new JsonArray();

                for (JsonElement e : raw) {
                    JsonObject r = e.getAsJsonObject();
                    arrivals.add(new BusArrival(
                            str(r, "shortName"),
                            str(r, "toStopName"),
                            str(r, "displayedTime"),
                            r.has("realtime") && r.get("realtime").getAsInt() == 1,
                            r.has("diffTime")            ? r.get("diffTime").getAsInt()            : Integer.MAX_VALUE,
                            r.has("displayedTimeColor")  ? r.get("displayedTimeColor").getAsInt()  : 0
                    ));
                }
            }
            // id values other than 0 and 1 are silently ignored for forward compatibility
        }

        // Sort by seconds until arrival (API usually pre-sorts, but defensive sort is cheap)
        Collections.sort(arrivals, (a, b) -> Integer.compare(a.diffTime, b.diffTime));

        // Truncate to the number of rows the renderer can display
        if (arrivals.size() > BuildConfig.MAX_BUS_ROWS) {
            arrivals = new ArrayList<>(arrivals.subList(0, BuildConfig.MAX_BUS_ROWS));
        }

        return new StationData(stationName, arrivals, refreshSeconds);
    }

    /** Returns the string value for {@code key}, or {@code ""} if the key is absent. */
    private static String str(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }
}
