package ro.smarttrans.busschedule.api;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ro.smarttrans.busschedule.BuildConfig;

public class ApiClient {

    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int READ_TIMEOUT_SEC    = 15;
    private static final int MAX_RETRIES         = 3;

    private final OkHttpClient http;

    public ApiClient() {
        http = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
    }

    /** Fetches schedule JSON for the given station ID. Retries up to 3 times with exponential backoff. */
    public String fetchSchedule(int stationId) throws IOException {
        String url = BuildConfig.API_BASE_URL + "?stid=" + stationId;
        android.util.Log.d("ApiClient", "fetchSchedule START — url=" + url);
        IOException last = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long sleepMs = (long) Math.pow(2, attempt) * 1000L;
                android.util.Log.d("ApiClient", "Retry " + attempt + "/" + (MAX_RETRIES - 1)
                        + " — sleeping " + sleepMs + "ms before next attempt");
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry", e);
                }
            }
            android.util.Log.d("ApiClient", "Attempt " + (attempt + 1) + "/" + MAX_RETRIES
                    + " — connecting to " + url);
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Accept", "application/json")
                        .build();
                try (Response resp = http.newCall(req).execute()) {
                    int code = resp.code();
                    String contentType = resp.header("Content-Type", "(none)");
                    android.util.Log.d("ApiClient", "HTTP " + code + " Content-Type=" + contentType);
                    if (!resp.isSuccessful()) {
                        android.util.Log.w("ApiClient", "Non-2xx response: HTTP " + code
                                + " — will retry if attempts remain");
                        throw new IOException("HTTP " + code);
                    }
                    String body = resp.body() != null ? resp.body().string() : null;
                    if (body == null || body.isEmpty()) {
                        android.util.Log.w("ApiClient", "Empty response body from server");
                        throw new IOException("Empty response body");
                    }
                    android.util.Log.d("ApiClient", "Response OK — length=" + body.length()
                            + " chars, preview=" + body.substring(0, Math.min(200, body.length())));
                    return body;
                }
            } catch (IOException e) {
                android.util.Log.w("ApiClient", "Attempt " + (attempt + 1) + " failed ["
                        + e.getClass().getSimpleName() + "]: " + e.getMessage());
                last = e;
            }
        }
        android.util.Log.e("ApiClient", "All " + MAX_RETRIES + " attempts exhausted — last error: "
                + (last != null ? last.getClass().getSimpleName() + ": " + last.getMessage() : "null"));
        throw last;
    }
}
