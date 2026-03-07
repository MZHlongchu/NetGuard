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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Virtual Hosts Manager
 * Allows users to define custom host mappings without root access
 */
public class VirtualHost {
    private static final String TAG = "NetGuard.VirtualHost";

    public static final String TABLE_NAME = "virtual_hosts";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_DOMAIN = "domain";
    public static final String COLUMN_IP = "ip_address";
    public static final String COLUMN_ENABLED = "enabled";
    public static final String COLUMN_SOURCE = "source";
    public static final String COLUMN_TIME = "time";

    // Source types
    public static final int SOURCE_USER = 0;
    public static final int SOURCE_GITHUB = 1;
    public static final int SOURCE_SYSTEM = 2;

    private long id;
    private String domain;
    private String ipAddress;
    private boolean enabled;
    private int source;
    private long time;

    public VirtualHost() {
    }

    public VirtualHost(String domain, String ipAddress, boolean enabled, int source) {
        this.domain = domain;
        this.ipAddress = ipAddress;
        this.enabled = enabled;
        this.source = source;
        this.time = System.currentTimeMillis();
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSource() {
        return source;
    }

    public void setSource(int source) {
        this.source = source;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    /**
     * Create the virtual hosts table
     */
    public static void createTable(SQLiteDatabase db) {
        Log.i(TAG, "Creating virtual_hosts table");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", " + COLUMN_DOMAIN + " TEXT NOT NULL" +
                ", " + COLUMN_IP + " TEXT NOT NULL" +
                ", " + COLUMN_ENABLED + " INTEGER NOT NULL DEFAULT 1" +
                ", " + COLUMN_SOURCE + " INTEGER NOT NULL DEFAULT 0" +
                ", " + COLUMN_TIME + " INTEGER NOT NULL" +
                ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vhosts_domain ON " + TABLE_NAME + "(" + COLUMN_DOMAIN + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vhosts_enabled ON " + TABLE_NAME + "(" + COLUMN_ENABLED + ");");
    }

    /**
     * Insert a virtual host entry
     */
    public long insert(Context context) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(COLUMN_DOMAIN, domain);
        values.put(COLUMN_IP, ipAddress);
        values.put(COLUMN_ENABLED, enabled ? 1 : 0);
        values.put(COLUMN_SOURCE, source);
        values.put(COLUMN_TIME, time);
        
        long result = db.insert(TABLE_NAME, null, values);
        if (result > 0) {
            id = result;
            Log.i(TAG, "Inserted virtual host: " + domain + " -> " + ipAddress);
        }
        return result;
    }

    /**
     * Update a virtual host entry
     */
    public int update(Context context) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(COLUMN_DOMAIN, domain);
        values.put(COLUMN_IP, ipAddress);
        values.put(COLUMN_ENABLED, enabled ? 1 : 0);
        values.put(COLUMN_SOURCE, source);
        
        return db.update(TABLE_NAME, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Delete a virtual host entry
     */
    public int delete(Context context) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getWritableDatabase();
        
        return db.delete(TABLE_NAME, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Get all virtual hosts
     */
    public static List<VirtualHost> getAll(Context context) {
        List<VirtualHost> list = new ArrayList<>();
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getReadableDatabase();
        
        Cursor cursor = db.query(TABLE_NAME, null, null, null, null, null, COLUMN_DOMAIN + " ASC");
        while (cursor.moveToNext()) {
            VirtualHost vh = fromCursor(cursor);
            list.add(vh);
        }
        cursor.close();
        
        return list;
    }

    /**
     * Get enabled virtual hosts only
     */
    public static List<VirtualHost> getEnabled(Context context) {
        List<VirtualHost> list = new ArrayList<>();
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getReadableDatabase();
        
        Cursor cursor = db.query(TABLE_NAME, null, 
                COLUMN_ENABLED + " = ?", new String[]{"1"}, 
                null, null, COLUMN_DOMAIN + " ASC");
        while (cursor.moveToNext()) {
            VirtualHost vh = fromCursor(cursor);
            list.add(vh);
        }
        cursor.close();
        
        return list;
    }

    /**
     * Get virtual host by domain
     */
    public static VirtualHost getByDomain(Context context, String domain) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getReadableDatabase();
        
        Cursor cursor = db.query(TABLE_NAME, null, 
                COLUMN_DOMAIN + " = ? AND " + COLUMN_ENABLED + " = ?", 
                new String[]{domain, "1"}, 
                null, null, null);
        
        VirtualHost vh = null;
        if (cursor.moveToFirst()) {
            vh = fromCursor(cursor);
        }
        cursor.close();
        
        return vh;
    }

    /**
     * Get IP address for domain from virtual hosts
     */
    public static String resolveDomain(Context context, String domain) {
        VirtualHost vh = getByDomain(context, domain);
        if (vh != null) {
            Log.i(TAG, "Virtual host resolved: " + domain + " -> " + vh.getIpAddress());
            return vh.getIpAddress();
        }
        
        // Also check for wildcard domains
        List<VirtualHost> all = getEnabled(context);
        for (VirtualHost v : all) {
            if (domain.endsWith(v.getDomain()) || domain.equals(v.getDomain())) {
                Log.i(TAG, "Virtual host wildcard resolved: " + domain + " -> " + v.getIpAddress());
                return v.getIpAddress();
            }
        }
        
        return null;
    }

    /**
     * Get virtual host count
     */
    public static int getCount(Context context) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getReadableDatabase();
        
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME, null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        
        return count;
    }

    /**
     * Get enabled virtual host count
     */
    public static int getEnabledCount(Context context) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getReadableDatabase();
        
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE " + COLUMN_ENABLED + " = 1", null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        
        return count;
    }

    /**
     * Clear all virtual hosts (except user-defined if specified)
     */
    public static int clearBySource(Context context, int source) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getWritableDatabase();
        
        return db.delete(TABLE_NAME, COLUMN_SOURCE + " = ?", new String[]{String.valueOf(source)});
    }

    /**
     * Clear all virtual hosts
     */
    public static int clearAll(Context context) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getWritableDatabase();
        
        return db.delete(TABLE_NAME, null, null);
    }

    /**
     * Bulk insert virtual hosts
     */
    public static int bulkInsert(Context context, List<VirtualHost> hosts, int source) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        SQLiteDatabase db = dh.getWritableDatabase();
        
        int count = 0;
        db.beginTransaction();
        try {
            // Clear existing entries from same source
            db.delete(TABLE_NAME, COLUMN_SOURCE + " = ?", new String[]{String.valueOf(source)});
            
            long now = System.currentTimeMillis();
            for (VirtualHost vh : hosts) {
                ContentValues values = new ContentValues();
                values.put(COLUMN_DOMAIN, vh.getDomain());
                values.put(COLUMN_IP, vh.getIpAddress());
                values.put(COLUMN_ENABLED, vh.isEnabled() ? 1 : 0);
                values.put(COLUMN_SOURCE, source);
                values.put(COLUMN_TIME, now);
                
                if (db.insert(TABLE_NAME, null, values) > 0) {
                    count++;
                }
            }
            db.setTransactionSuccessful();
            Log.i(TAG, "Bulk inserted " + count + " virtual hosts from source " + source);
        } finally {
            db.endTransaction();
        }
        
        return count;
    }

    /**
     * Convert cursor to VirtualHost object
     */
    private static VirtualHost fromCursor(Cursor cursor) {
        VirtualHost vh = new VirtualHost();
        vh.setId(cursor.getLong(cursor.getColumnIndex(COLUMN_ID)));
        vh.setDomain(cursor.getString(cursor.getColumnIndex(COLUMN_DOMAIN)));
        vh.setIpAddress(cursor.getString(cursor.getColumnIndex(COLUMN_IP)));
        vh.setEnabled(cursor.getInt(cursor.getColumnIndex(COLUMN_ENABLED)) == 1);
        vh.setSource(cursor.getInt(cursor.getColumnIndex(COLUMN_SOURCE)));
        vh.setTime(cursor.getLong(cursor.getColumnIndex(COLUMN_TIME)));
        return vh;
    }

    @Override
    public String toString() {
        return domain + " -> " + ipAddress;
    }
}
