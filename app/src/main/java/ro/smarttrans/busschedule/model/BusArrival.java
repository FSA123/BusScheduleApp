package ro.smarttrans.busschedule.model;

public class BusArrival {
    public final String shortName;        // bus line number, e.g. "4", "10"
    public final String toStopName;       // destination stop name
    public final String displayedTime;    // "5min" (imminent) or "11:29" (clock time)
    public final boolean realtime;        // true = GPS-tracked, false = scheduled only
    public final int diffTime;            // seconds until arrival, used for sorting
    public final int displayedTimeColor;  // 3855 = green/imminent, 4080 = orange/scheduled

    public BusArrival(String shortName, String toStopName, String displayedTime,
                      boolean realtime, int diffTime, int displayedTimeColor) {
        this.shortName = shortName;
        this.toStopName = toStopName;
        this.displayedTime = displayedTime.trim();
        this.realtime = realtime;
        this.diffTime = diffTime;
        this.displayedTimeColor = displayedTimeColor;
    }

    public boolean isImminent() {
        return displayedTimeColor == 3855;
    }
}
