package eu.faircode.netguard;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2025 by Marcel Bokhorst (M66B)
*/

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

/**
 * Schedules automatic virtual hosts updates
 */
public class VirtualHostUpdateScheduler {
    private static final String TAG = "NetGuard.VHostScheduler";
    
    public static final String ACTION_UPDATE_VHOSTS = "eu.faircode.netguard.UPDATE_VHOSTS";
    public static final int REQUEST_CODE_UPDATE = 1001;

    /**
     * Schedule automatic virtual hosts update
     */
    public static void scheduleUpdate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        
        boolean autoUpdate = prefs.getBoolean(VirtualHostDownloader.KEY_AUTO_UPDATE, true);
        if (!autoUpdate) {
            cancelUpdate(context);
            Log.i(TAG, "Auto update disabled");
            return;
        }
        
        // Get update interval
        String intervalStr = prefs.getString(VirtualHostDownloader.KEY_UPDATE_INTERVAL, 
            String.valueOf(VirtualHostDownloader.INTERVAL_WEEKLY));
        long interval = Long.parseLong(intervalStr);
        
        // Calculate next update time
        long lastUpdate = prefs.getLong(VirtualHostDownloader.KEY_LAST_UPDATE, 0);
        long nextUpdate = lastUpdate + interval;
        
        // If no previous update, start from now
        if (lastUpdate == 0) {
            nextUpdate = System.currentTimeMillis() + interval;
        }
        
        // Schedule alarm
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.e(TAG, "Failed to get AlarmManager");
            return;
        }
        
        Intent intent = new Intent(context, VirtualHostUpdateReceiver.class);
        intent.setAction(ACTION_UPDATE_VHOSTS);
        
        PendingIntent pi = PendingIntent.getBroadcast(
            context, 
            REQUEST_CODE_UPDATE, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        try {
            // Use inexact alarm for battery efficiency
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                nextUpdate,
                interval,
                pi
            );
            Log.i(TAG, "Scheduled update in " + (nextUpdate - System.currentTimeMillis()) / 1000 / 60 + " minutes");
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule update: " + e.getMessage());
        }
    }

    /**
     * Cancel scheduled update
     */
    public static void cancelUpdate(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        
        Intent intent = new Intent(context, VirtualHostUpdateReceiver.class);
        intent.setAction(ACTION_UPDATE_VHOSTS);
        
        PendingIntent pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_UPDATE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        am.cancel(pi);
        Log.i(TAG, "Cancelled scheduled update");
    }

    /**
     * Broadcast receiver for scheduled updates
     */
    public static class VirtualHostUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_UPDATE_VHOSTS.equals(intent.getAction())) {
                Log.i(TAG, "Starting scheduled virtual hosts update");
                
                // Download hosts
                VirtualHostDownloader downloader = new VirtualHostDownloader(context);
                downloader.setListener(new VirtualHostDownloader.DownloadListener() {
                    @Override
                    public void onStart() {
                        Log.i(TAG, "Starting scheduled download");
                    }

                    @Override
                    public void onProgress(int progress, String message) {
                        Log.i(TAG, "Progress: " + progress + "% - " + message);
                    }

                    @Override
                    public void onSuccess(int count, String message) {
                        Log.i(TAG, "Update success: " + count + " hosts");
                        
                        // Reload service to apply new hosts
                        ServiceSinkhole.reload("Virtual hosts updated", context, false);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Update error: " + error);
                    }
                });
                
                downloader.download();
            }
        }
    }
}
