package ro.smarttrans.busschedule.renderer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import ro.smarttrans.busschedule.model.BusArrival;
import ro.smarttrans.busschedule.model.StationData;

public class ScheduleRenderer {
    private static final String TAG = "ScheduleRenderer";

    // One color per bus line — chosen by hash of shortName so the same line always gets the same color
    private static final int[] LINE_COLORS = {
            0xFF2980B9, 0xFFE74C3C, 0xFF27AE60, 0xFFF39C12,
            0xFF9B59B6, 0xFF1ABC9C, 0xFFE67E22, 0xFF16A085,
            0xFF8E44AD, 0xFF2C3E50, 0xFFD35400, 0xFF2471A3
    };

    private static final String TIMEZONE = "Europe/Bucharest";

    private final int W;
    private final int H;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Separate paint used only for text measurement — never drawn with. */
    private final Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScheduleRenderer(int displayWidth, int displayHeight) {
        this.W = displayWidth;
        this.H = displayHeight;
    }

    // ── Layout geometry (public so BusScheduleService can compute crop rects) ──

    public int headerH()              { return (int) (H * 0.14f); }
    public int colH()                 { return (int) (H * 0.08f); }
    public int footerH()              { return (int) (H * 0.09f); }
    public int rowH(int rowCount)     { return (H - headerH() - colH() - footerH()) / Math.max(1, rowCount); }
    public int rowTop(int i, int n)   { return headerH() + colH() + i * rowH(n); }
    public int footerTop()            { return H - footerH(); }

    /**
     * Returns the pixel x-coordinate of the left edge of the clock text in the header.
     * All "HH:mm" strings have identical width in monospace, so "00:00" gives a safe reference.
     */
    public int clockTextLeft() {
        mp.setTypeface(Typeface.MONOSPACE);
        mp.setTextSize(headerH() * 0.37f);
        return (int) (W * 0.975f - mp.measureText("00:00"));
    }

    /**
     * Returns the pixel width of the given arrival-time string as it is drawn in a row.
     * Matches the font/size used in drawRows exactly.
     */
    public int measureTimeWidth(String text, boolean isImminent, int rH) {
        mp.setTypeface(isImminent
                ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                : Typeface.MONOSPACE);
        mp.setTextSize(rH * 0.43f);
        return (int) Math.ceil(mp.measureText(text));
    }

    /**
     * Returns the pixel x-coordinate of the left edge of the "Actualizat: HH:mm" footer text.
     */
    public int footerTimestampLeft() {
        mp.setTypeface(Typeface.DEFAULT);
        mp.setTextSize(footerH() * 0.45f);
        return (int) (W * 0.975f - mp.measureText("Actualizat: 00:00"));
    }

    private SimpleDateFormat clockFmt() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getTimeZone(TIMEZONE));
        return sdf;
    }

    /**
     * Renders only the header stripe (station name + current clock) as a standalone bitmap.
     * Used for clock-only partial refreshes between data fetches.
     */
    public Bitmap renderHeaderPatch(String stationName) {
        int hH = headerH();
        Bitmap bmp = Bitmap.createBitmap(W, hH, Bitmap.Config.ARGB_8888);
        drawHeader(new Canvas(bmp), stationName, hH);
        return bmp;
    }

    public Bitmap render(StationData station, boolean offline, long lastUpdatedMs) {
        Log.d(TAG, "render start — " + W + "x" + H + " station='" + station.name
                + "' arrivals=" + station.arrivals.size() + " offline=" + offline);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        int headerH = headerH();
        int colH    = colH();
        int footerH = footerH();
        int rows    = Math.max(1, station.arrivals.size());
        int rowH    = rowH(rows);

        drawHeader(c, station.name, headerH);
        drawColHeaders(c, headerH, colH);
        drawRows(c, station.arrivals, headerH + colH, rowH);
        drawFooter(c, offline, lastUpdatedMs, footerTop(), footerH);

        Log.d(TAG, "render done — bitmap " + bmp.getWidth() + "x" + bmp.getHeight());
        return bmp;
    }

    // ── Header: dark bar with station name left, clock right ──────────────────
    private void drawHeader(Canvas c, String stationName, int headerH) {
        p.setColor(0xFF1A252F);
        c.drawRect(0, 0, W, headerH, p);

        float y = headerH * 0.66f;

        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(headerH * 0.65f);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(stationName.toUpperCase(Locale.ROOT), W * 0.025f, y, p);

        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(headerH * 0.37f);
        p.setTextAlign(Paint.Align.RIGHT);
        String clock = clockFmt().format(new Date());
        c.drawText(clock, W * 0.975f, y, p);

        p.setTypeface(Typeface.DEFAULT);
    }

    // ── Column labels ──────────────────────────────────────────────────────────
    private void drawColHeaders(Canvas c, int top, int colH) {
        p.setColor(0xFF2C3E50);
        c.drawRect(0, top, W, top + colH, p);

        p.setColor(0xFFBDC3C7);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(colH * 0.50f);
        float y = top + colH * 0.70f;

        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("Linie", W * 0.105f, y, p);

        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("Destinatie", W * 0.225f, y, p);

        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Sosire", W * 0.975f, y, p);

        p.setTypeface(Typeface.DEFAULT);
    }

    // ── Bus rows ───────────────────────────────────────────────────────────────
    private void drawRows(Canvas c, List<BusArrival> arrivals, int top, int rowH) {
        for (int i = 0; i < arrivals.size(); i++) {
            BusArrival bus = arrivals.get(i);
            int rTop = top + i * rowH;

            // Alternating row background
            p.setColor(i % 2 == 0 ? Color.WHITE : 0xFFF2F3F4);
            c.drawRect(0, rTop, W, rTop + rowH, p);

            // Bottom divider
            p.setColor(0xFFDCDCDC);
            p.setStrokeWidth(1f);
            c.drawLine(0, rTop + rowH - 1, W, rTop + rowH - 1, p);

            float midY = rTop + rowH * 0.64f;

            // Colored line-number badge
            drawBadge(c, bus.shortName,
                    W * 0.018f, rTop + rowH * 0.12f,
                    W * 0.195f, rTop + rowH * 0.88f);

            // Destination text — set text size before calling truncate
            p.setColor(0xFF2C3E50);
            p.setTextSize(rowH * 0.40f);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Typeface.DEFAULT);
            c.drawText(truncate(bus.toStopName, W * 0.52f), W * 0.225f, midY, p);

            // Real-time GPS dot (green circle to the left of the time column)
            if (bus.realtime) {
                p.setColor(0xFF27AE60);
                c.drawCircle(W * 0.775f, rTop + rowH * 0.44f, rowH * 0.10f, p);
            }

            // Time text: bold green = real-time imminent, dark yellow = scheduled imminent, gray = far
            int timeColor = bus.isImminent()
                    ? (bus.realtime ? 0xFF1E8449 : 0xFFD4AC0D)
                    : 0xFF717D7E;
            p.setColor(timeColor);
            p.setTextSize(rowH * 0.43f);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTypeface(bus.isImminent()
                    ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    : Typeface.MONOSPACE);
            c.drawText(bus.displayedTime, W * 0.975f, midY, p);
        }
        p.setTypeface(Typeface.DEFAULT);
    }

    // ── Colored rounded-rect badge with white line number text ────────────────
    private void drawBadge(Canvas c, String lineNum, float l, float t, float r, float b) {
        int color = LINE_COLORS[Math.abs(lineNum.hashCode()) % LINE_COLORS.length];
        float radius = (b - t) * 0.25f;

        p.setColor(color);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);

        float ts = (b - t) * 0.58f;
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(ts);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(lineNum, (l + r) / 2f, (t + b) / 2f + ts * 0.35f, p);
    }

    // ── Footer: LIVE / OFFLINE indicator + last-updated timestamp ─────────────
    private void drawFooter(Canvas c, boolean offline, long lastUpdatedMs, int top, int footerH) {
        p.setColor(0xFFEAECEE);
        c.drawRect(0, top, W, top + footerH, p);

        p.setColor(0xFFBDC3C7);
        p.setStrokeWidth(1f);
        c.drawLine(0, top, W, top, p);

        float y = top + footerH * 0.68f;
        p.setTextSize(footerH * 0.45f);

        if (offline) {
            p.setColor(0xFFCB4335);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("  ⚠ FARA CONEXIUNE", W * 0.025f, y, p);
        } else {
            p.setColor(0xFF1E8449);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("  ● LIVE", W * 0.025f, y, p);
        }

        if (lastUpdatedMs > 0) {
            String ts = clockFmt().format(new Date(lastUpdatedMs));
            p.setColor(0xFF717D7E);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("Actualizat: " + ts, W * 0.975f, y, p);
        }

        p.setTypeface(Typeface.DEFAULT);
    }

    /** White screen with "TRANSPORT PUBLIC" centered in bold — used as a startup diagnostic frame. */
    public Bitmap renderSplash() {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        p.setColor(0xFF1A252F);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(H * 0.14f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("TRANSPORT PUBLIC", W / 2f, H / 2f + H * 0.05f, p);

        p.setTypeface(Typeface.DEFAULT);
        Log.d(TAG, "renderSplash done — " + W + "x" + H);
        return bmp;
    }

    /** Renders a plain "no connection" screen shown before any data has ever been cached. */
    public Bitmap renderOfflinePlaceholder() {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        p.setColor(0xFF1A252F);
        c.drawRect(0, 0, W, H * 0.14f, p);

        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(H * 0.06f);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("STATIE BUS", W * 0.025f, H * 0.095f, p);

        p.setColor(0xFFCB4335);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(H * 0.09f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("Fara conexiune", W / 2f, H * 0.50f, p);

        p.setColor(0xFF717D7E);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(H * 0.055f);
        c.drawText("Se reincearca conectarea...", W / 2f, H * 0.62f, p);

        p.setTypeface(Typeface.DEFAULT);
        return bmp;
    }

    // ── Truncate text with ellipsis to fit within maxPx wide ──────────────────
    private String truncate(String text, float maxPx) {
        if (p.measureText(text) <= maxPx) return text;
        int len = text.length();
        while (len > 0 && p.measureText(text.substring(0, len) + "…") > maxPx) len--;
        return text.substring(0, len) + "…";
    }
}
