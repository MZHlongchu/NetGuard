package eu.faircode.netguard;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ActivityVirtualHosts extends AppCompatActivity {
    private static final String TAG = "NetGuard.VHosts";

    private static final String GITHUB520_URL = "https://raw.hellogithub.com/hosts";

    private boolean running;
    private AdapterVirtualHosts adapter = null;
    private SwitchCompat swEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.virtual_hosts);

        getSupportActionBar().setTitle(R.string.title_virtual_hosts);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        swEnabled = findViewById(R.id.swVirtualHostsEnabled);
        swEnabled.setChecked(prefs.getBoolean("use_virtual_hosts", false));
        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("use_virtual_hosts", isChecked).apply();
                ServiceSinkhole.reload("virtual hosts", ActivityVirtualHosts.this, false);
            }
        });

        ListView lvHosts = findViewById(R.id.lvVirtualHosts);
        adapter = new AdapterVirtualHosts(this, DatabaseHelper.getInstance(this).getVirtualHosts());
        lvHosts.setAdapter(adapter);

        running = true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.virtual_hosts, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_add_host) {
            showAddDialog();
            return true;
        } else if (id == R.id.menu_update_github) {
            updateFromGitHub520();
            return true;
        } else if (id == R.id.menu_clear_hosts) {
            Util.areYouSure(this, R.string.menu_clear, new Util.DoubtListener() {
                @Override
                public void onSure() {
                    clearHosts();
                }
            });
            return true;
        } else if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAddDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.virtual_host_add, null);
        final EditText etHostname = view.findViewById(R.id.etHostname);
        final EditText etIp = view.findViewById(R.id.etIp);

        new AlertDialog.Builder(this)
                .setTitle(R.string.vhost_add_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String hostname = etHostname.getText().toString().trim();
                        String ip = etIp.getText().toString().trim();
                        if (hostname.length() > 0 && ip.length() > 0) {
                            DatabaseHelper.getInstance(ActivityVirtualHosts.this)
                                    .insertVirtualHost(hostname, ip, true, "manual");
                            updateAdapter();
                            ServiceSinkhole.reload("virtual hosts", ActivityVirtualHosts.this, false);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateFromGitHub520() {
        Toast.makeText(this, R.string.vhost_updating, Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Void, Object>() {
            @Override
            protected Object doInBackground(Void... voids) {
                HttpURLConnection conn = null;
                try {
                    List<String[]> entries = new ArrayList<>();
                    URL url = new URL(GITHUB520_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "NetGuard");

                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        int hash = line.indexOf('#');
                        if (hash >= 0)
                            line = line.substring(0, hash);
                        line = line.trim();
                        if (line.length() > 0) {
                            String[] words = line.split("\\s+");
                            if (words.length >= 2) {
                                String ip = words[0];
                                String host = words[1].toLowerCase();
                                // Only valid IP + hostname
                                if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && host.contains(".")) {
                                    entries.add(new String[]{host, ip});
                                }
                            }
                        }
                    }
                    br.close();

                    if (entries.size() > 0) {
                        DatabaseHelper.getInstance(ActivityVirtualHosts.this)
                                .importVirtualHosts(entries, "github520");
                    }
                    return entries.size();
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                } finally {
                    if (conn != null)
                        conn.disconnect();
                }
            }

            @Override
            protected void onPostExecute(Object result) {
                if (!running)
                    return;
                if (result instanceof Throwable) {
                    Toast.makeText(ActivityVirtualHosts.this,
                            ((Throwable) result).getMessage(), Toast.LENGTH_LONG).show();
                } else {
                    int count = (int) result;
                    Toast.makeText(ActivityVirtualHosts.this,
                            getString(R.string.vhost_updated, count), Toast.LENGTH_LONG).show();
                    updateAdapter();
                    ServiceSinkhole.reload("virtual hosts", ActivityVirtualHosts.this, false);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void clearHosts() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                DatabaseHelper.getInstance(ActivityVirtualHosts.this).clearVirtualHosts(null);
                return null;
            }

            @Override
            protected void onPostExecute(Void v) {
                updateAdapter();
                ServiceSinkhole.reload("virtual hosts", ActivityVirtualHosts.this, false);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void updateAdapter() {
        if (adapter != null)
            adapter.changeCursor(DatabaseHelper.getInstance(this).getVirtualHosts());
    }

    @Override
    protected void onDestroy() {
        running = false;
        adapter = null;
        super.onDestroy();
    }
}
