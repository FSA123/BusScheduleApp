package ro.smarttrans.busschedule;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import ro.smarttrans.busschedule.service.BusScheduleService;

public class BusScheduleApp extends Application {
    private static final String TAG = "BusScheduleApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "App started — station=" + BuildConfig.STATION_ID
                + " display=" + BuildConfig.DISPLAY_WIDTH + "x" + BuildConfig.DISPLAY_HEIGHT);

        Intent svc = new Intent(this, BusScheduleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        Log.i(TAG, "BusScheduleService start requested");
    }
}
