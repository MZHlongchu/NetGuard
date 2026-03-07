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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Virtual Host Downloader
 * Downloads and updates hosts files from GitHub
 */
public class VirtualHostDownloader {
    private static final String TAG = "NetGuard.VHostDownloader";

    // Default GitHub hosts sources (mirrors for China access)
    // These are popular hosts files that help access GitHub from China
    public static final String DEFAULT_HOSTS_URL = 
        "https://raw.githubusercontent.com/AlanJui/GitHub-Host/main/hosts";
    
    public static final String FALLBACK_HOSTS_URL = 
        "https://raw.githubusercontent.com/hiddify/GitHub-Host/main/CF-Events/hosts";

    public static final String MIRROR_HOSTS_URL = 
        "https://raw.githubusercontent.com/Whoisbel/CloudflareSpeedTestHosts/main/hosts";

    public static final String KEY_LAST_UPDATE = "vhosts_last_update";
    public static final String KEY_UPDATE_INTERVAL = "vhosts_update_interval";
    public static final String KEY_AUTO_UPDATE = "vhosts_auto_update";
    public static final String KEY_ENABLED = "vhosts_enabled";

    // Update intervals in milliseconds
    public static final long INTERVAL_DAILY = 24 * 60 * 60 * 1000L;
    public static final long INTERVAL_WEEKLY = 7 * 24 * 60 * 60 * 1000L;
    public static final long INTERVAL_MONTHLY = 30 * 24 * 60 * 60 * 1000L;

    private Context context;
    private ExecutorService executor;
    private Handler mainHandler;
    private DownloadListener listener;

    public interface DownloadListener {
        void onStart();
        void onProgress(int progress, String message);
        void onSuccess(int count, String message);
        void onError(String error);
    }

    public VirtualHostDownloader(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(DownloadListener listener) {
        this.listener = listener;
    }

    /**
     * Download and update hosts from GitHub
     */
    public void download() {
        download(DEFAULT_HOSTS_URL);
    }

    /**
     * Download hosts from specified URL
     */
    public void download(final String urlString) {
        if (listener != null) {
            mainHandler.post(() -> listener.onStart());
        }

        executor.execute(() -> {
            Log.i(TAG, "Starting hosts download from: " + urlString);
            
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "NetGuard/1.0");
                conn.setUseCaches(false);

                int responseCode = conn.getResponseCode();
                Log.i(TAG, "Response code: " + responseCode);

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP error: " + responseCode);
                }

                if (listener != null) {
                    mainHandler.post(() -> listener.onProgress(50, "Downloading hosts..."));
                }

                List<VirtualHost> hosts = parseHosts(conn);
                
                if (hosts.isEmpty()) {
                    // Try fallback URL
                    Log.w(TAG, "No hosts parsed, trying fallback URL");
                    hosts = downloadFromUrl(FALLBACK_HOSTS_URL);
                }

                if (hosts.isEmpty() && listener != null) {
                    mainHandler.post(() -> listener.onError("No hosts found in response"));
                    return;
                }

                // Save to database
                int count = VirtualHost.bulkInsert(context, hosts, VirtualHost.SOURCE_GITHUB);
                
                // Update last update time
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply();

                Log.i(TAG, "Successfully imported " + count + " hosts");

                if (listener != null) {
                    mainHandler.post(() -> listener.onSuccess(count, 
                        context.getString(R.string.msg_vhosts_updated, count)));
                }

            } catch (Exception e) {
                Log.e(TAG, "Download error: " + e.getMessage());
                if (listener != null) {
                    mainHandler.post(() -> listener.onError(e.getMessage()));
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Download from URL and return list of VirtualHost
     */
    private List<VirtualHost> downloadFromUrl(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "NetGuard/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return parseHosts(conn);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fallback download error: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return new ArrayList<>();
    }

    /**
     * Parse hosts file content
     */
    private List<VirtualHost> parseHosts(HttpURLConnection conn) {
        List<VirtualHost> hosts = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            int lineNum = 0;
            int parsed = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }

                // Parse hosts file line: IPAddress Hostname [#comment]
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String ip = parts[0];
                    String domain = parts[1];

                    // Validate IP address
                    if (isValidIP(ip)) {
                        // Handle wildcard domains (e.g., *.github.com)
                        if (domain.startsWith("*.")) {
                            domain = domain.substring(2);
                        }
                        
                        VirtualHost vh = new VirtualHost(domain, ip, true, VirtualHost.SOURCE_GITHUB);
                        hosts.add(vh);
                        parsed++;
                    }
                }

                // Progress update every 100 lines
                if (lineNum % 100 == 0 && listener != null) {
                    final int progress = 50 + (parsed / 10);
                    final String msg = "Parsing... " + parsed + " hosts";
                    mainHandler.post(() -> listener.onProgress(Math.min(progress, 90), msg));
                }
            }

            Log.i(TAG, "Parsed " + parsed + " hosts from " + lineNum + " lines");

        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }

        return hosts;
    }

    /**
     * Validate IP address
     */
    private boolean isValidIP(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // IPv4 validation
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                try {
                    for (String part : parts) {
                        int num = Integer.parseInt(part);
                        if (num < 0 || num > 255) {
                            return false;
                        }
                    }
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }

        // IPv6 validation (basic)
        if (ip.contains(":")) {
            return ip.contains("::") || ip.split(":").length <= 8;
        }

        return false;
    }

    /**
     * Check if update is needed
     */
    public boolean isUpdateNeeded() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        
        if (!prefs.getBoolean(KEY_AUTO_UPDATE, true)) {
            return false;
        }

        long lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0);
        long interval = Long.parseLong(prefs.getString(KEY_UPDATE_INTERVAL, 
            String.valueOf(INTERVAL_WEEKLY)));

        return (System.currentTimeMillis() - lastUpdate) > interval;
    }

    /**
     * Get last update time
     */
    public long getLastUpdateTime() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getLong(KEY_LAST_UPDATE, 0);
    }

    /**
     * Check if virtual hosts is enabled
     */
    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    /**
     * Set virtual hosts enabled state
     */
    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * Clean up resources
     */
    public void destroy() {
        executor.shutdown();
    }
}
