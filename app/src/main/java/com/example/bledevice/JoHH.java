package com.example.bledevice;

import android.util.Log;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class JoHH {
    private static final String TAG = "JoHH";
    private static final Map<String, Long> rateLimits = new HashMap<>();

    public static long tsl() {
        return System.currentTimeMillis();
    }
    public static synchronized boolean ratelimit(String name, int seconds) {
        // check if over limit
        if ((rateLimits.containsKey(name)) && (JoHH.tsl() - rateLimits.get(name) < (seconds * 1000L))) {
            Log.d(TAG, name + " rate limited: " + seconds + " seconds");
            return false;
        }
        // not over limit
        rateLimits.put(name, JoHH.tsl());
        return true;
    }
    public static synchronized boolean pratelimit(String name, int seconds) {
        // check if over limit
        final long time_now = JoHH.tsl();
        final long rate_time;
        if (!rateLimits.containsKey(name)) {
            rate_time = PersistentStore.getLong(name); // 0 if undef
        } else {
            rate_time = rateLimits.get(name);
        }
        if ((rate_time > 0) && (time_now - rate_time) < (seconds * 1000L)) {
            Log.d(TAG, name + " rate limited: " + seconds + " seconds");
            return false;
        }
        // not over limit
        rateLimits.put(name, time_now);
        PersistentStore.setLong(name, time_now);
        return true;
    }
    public static int parseIntWithDefault(String number, int radix, int defaultVal) {
        try {
            return Integer.parseInt(number, radix);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing integer number = " + number + " radix = " + radix);
            return defaultVal;
        }
    }
    public static String dateTimeText(long timestamp) {
        return android.text.format.DateFormat.format("yyyy-MM-dd kk:mm:ss", timestamp).toString();
    }
}
