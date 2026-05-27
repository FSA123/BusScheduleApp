package ro.smarttrans.busschedule.ui;

import android.content.Intent;
import android.os.Build;
import androidx.core.content.ContextCompat;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import ro.smarttrans.busschedule.BuildConfig;
import ro.smarttrans.busschedule.databinding.ActivityMainBinding;
import ro.smarttrans.busschedule.service.BusScheduleService;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.etStationId.setText(String.valueOf(BuildConfig.STATION_ID));

        binding.btnStart.setOnClickListener(v -> {
            int id = parseStationId();
            Intent intent = new Intent(this, BusScheduleService.class);
            intent.setAction(BusScheduleService.ACTION_SET_STATION);
            intent.putExtra(BusScheduleService.EXTRA_STATION_ID, id);
            // Use ContextCompat to start a foreground service in a backwards-compatible way
            ContextCompat.startForegroundService(this, intent);
            binding.tvStatus.setText("Running — station " + id);
        });

        binding.btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, BusScheduleService.class));
            binding.tvStatus.setText("Stopped");
        });

        binding.tvStatus.setText("Running — station " + BuildConfig.STATION_ID);
    }

    private int parseStationId() {
        try {
            return Integer.parseInt(binding.etStationId.getText().toString().trim());
        } catch (NumberFormatException e) {
            return BuildConfig.STATION_ID;
        }
    }
}
