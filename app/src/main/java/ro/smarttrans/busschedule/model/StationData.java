package ro.smarttrans.busschedule.model;

import java.util.List;

public class StationData {
    public final String name;
    public final List<BusArrival> arrivals;
    public final int refreshIntervalSeconds; // from pdel field in API response

    public StationData(String name, List<BusArrival> arrivals, int refreshIntervalSeconds) {
        this.name = name;
        this.arrivals = arrivals;
        this.refreshIntervalSeconds = refreshIntervalSeconds;
    }
}
