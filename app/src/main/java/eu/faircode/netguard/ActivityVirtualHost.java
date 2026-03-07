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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cursoradapter.widget.SimpleCursorAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Activity for managing Virtual Hosts
 * Allows users to add/edit/delete custom host mappings
 * and download hosts from GitHub
 */
public class ActivityVirtualHost extends AppCompatActivity {
    private static final String TAG = "NetGuard.VHost";

    private RecyclerView recyclerView;
    private VirtualHostAdapter adapter;
    private TextView tvEmpty;
    private TextView tvStats;
    private ProgressBar progressBar;
    private FloatingActionButton fabAdd;

    private boolean running = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VirtualHostDownloader downloader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.virtual_host);

        getSupportActionBar().setTitle(R.string.title_virtual_hosts);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Initialize views
        recyclerView = findViewById(R.id.recyclerView);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvStats = findViewById(R.id.tvStats);
        progressBar = findViewById(R.id.progressBar);
        fabAdd = findViewById(R.id.fabAdd);

        // Setup RecyclerView
        adapter = new VirtualHostAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // FAB click - add new host
        fabAdd.setOnClickListener(v -> showAddHostDialog());

        // Initialize downloader
        downloader = new VirtualHostDownloader(this);
        downloader.setListener(new VirtualHostDownloader.DownloadListener() {
            @Override
            public void onStart() {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.VISIBLE);
                    fabAdd.setEnabled(false);
                });
            }

            @Override
            public void onProgress(int progress, String message) {
                runOnUiThread(() -> {
                    progressBar.setProgress(progress);
                    tvStats.setText(message);
                });
            }

            @Override
            public void onSuccess(int count, String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    fabAdd.setEnabled(true);
                    Toast.makeText(ActivityVirtualHost.this, message, Toast.LENGTH_LONG).show();
                    refreshList();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    fabAdd.setEnabled(true);
                    Toast.makeText(ActivityVirtualHost.this, 
                        getString(R.string.msg_error) + ": " + error, Toast.LENGTH_LONG).show();
                });
            }
        });

        refreshList();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.virtual_host, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        PackageManager pm = getPackageManager();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;

            case R.id.menu_download:
                downloadHosts();
                return true;

            case R.id.menu_refresh:
                refreshList();
                return true;

            case R.id.menu_clear:
                showClearConfirmDialog();
                return true;
        }
        return false;
    }

    private void refreshList() {
        new AsyncTask<Object, Object, List<VirtualHost>>() {
            @Override
            protected List<VirtualHost> doInBackground(Object... objects) {
                return VirtualHost.getAll(ActivityVirtualHost.this);
            }

            @Override
            protected void onPostExecute(List<VirtualHost> result) {
                if (running) {
                    adapter.setData(result);
                    updateStats(result);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void updateStats(List<VirtualHost> hosts) {
        int total = hosts.size();
        int enabled = 0;
        int userDefined = 0;
        int github = 0;

        for (VirtualHost vh : hosts) {
            if (vh.isEnabled()) enabled++;
            if (vh.getSource() == VirtualHost.SOURCE_USER) userDefined++;
            if (vh.getSource() == VirtualHost.SOURCE_GITHUB) github++;
        }

        String stats = String.format(Locale.getDefault(),
            getString(R.string.msg_vhosts_stats),
            total, enabled, userDefined, github);
        
        tvStats.setText(stats);
        tvEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
    }

    private void downloadHosts() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.title_download_hosts)
            .setMessage(R.string.msg_download_hosts)
            .setPositiveButton(R.string.title_download, (dialog, which) -> {
                downloader.download();
            })
            .setNegativeButton(R.string.title_cancel, null)
            .show();
    }

    private void showAddHostDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_virtual_host, null);
        EditText etDomain = dialogView.findViewById(R.id.etDomain);
        EditText etIp = dialogView.findViewById(R.id.etIp);
        CheckBox cbEnabled = dialogView.findViewById(R.id.cbEnabled);

        new AlertDialog.Builder(this)
            .setTitle(R.string.title_add_host)
            .setView(dialogView)
            .setPositiveButton(R.string.title_save, (dialog, which) -> {
                String domain = etDomain.getText().toString().trim();
                String ip = etIp.getText().toString().trim();
                boolean enabled = cbEnabled.isChecked();

                if (domain.isEmpty() || ip.isEmpty()) {
                    Toast.makeText(this, R.string.msg_invalid_input, Toast.LENGTH_SHORT).show();
                    return;
                }

                addHost(domain, ip, enabled);
            })
            .setNegativeButton(R.string.title_cancel, null)
            .show();
    }

    private void addHost(String domain, String ip, boolean enabled) {
        new AsyncTask<Object, Object, Long>() {
            @Override
            protected Long doInBackground(Object... objects) {
                VirtualHost vh = new VirtualHost(domain, ip, enabled, VirtualHost.SOURCE_USER);
                return vh.insert(ActivityVirtualHost.this);
            }

            @Override
            protected void onPostExecute(Long result) {
                if (result > 0) {
                    Toast.makeText(ActivityVirtualHost.this, R.string.msg_host_added, Toast.LENGTH_SHORT).show();
                    refreshList();
                } else {
                    Toast.makeText(ActivityVirtualHost.this, R.string.msg_error, Toast.LENGTH_SHORT).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showEditHostDialog(VirtualHost vh) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_virtual_host, null);
        EditText etDomain = dialogView.findViewById(R.id.etDomain);
        EditText etIp = dialogView.findViewById(R.id.etIp);
        CheckBox cbEnabled = dialogView.findViewById(R.id.cbEnabled);

        etDomain.setText(vh.getDomain());
        etIp.setText(vh.getIpAddress());
        cbEnabled.setChecked(vh.isEnabled());

        new AlertDialog.Builder(this)
            .setTitle(R.string.title_edit_host)
            .setView(dialogView)
            .setPositiveButton(R.string.title_save, (dialog, which) -> {
                String domain = etDomain.getText().toString().trim();
                String ip = etIp.getText().toString().trim();
                boolean enabled = cbEnabled.isChecked();

                if (domain.isEmpty() || ip.isEmpty()) {
                    Toast.makeText(this, R.string.msg_invalid_input, Toast.LENGTH_SHORT).show();
                    return;
                }

                vh.setDomain(domain);
                vh.setIpAddress(ip);
                vh.setEnabled(enabled);
                vh.update(ActivityVirtualHost.this);
                refreshList();
                Toast.makeText(ActivityVirtualHost.this, R.string.msg_host_updated, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.title_cancel, null)
            .show();
    }

    private void showDeleteConfirmDialog(VirtualHost vh) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.title_delete_host)
            .setMessage(getString(R.string.msg_delete_host, vh.getDomain()))
            .setPositiveButton(R.string.title_delete, (dialog, which) -> {
                vh.delete(ActivityVirtualHost.this);
                refreshList();
                Toast.makeText(ActivityVirtualHost.this, R.string.msg_host_deleted, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.title_cancel, null)
            .show();
    }

    private void showClearConfirmDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.title_clear_hosts)
            .setMessage(R.string.msg_clear_hosts)
            .setPositiveButton(R.string.title_clear, (dialog, which) -> {
                new AsyncTask<Object, Object, Integer>() {
                    @Override
                    protected Integer doInBackground(Object... objects) {
                        // Clear GitHub hosts only
                        return VirtualHost.clearBySource(ActivityVirtualHost.this, VirtualHost.SOURCE_GITHUB);
                    }

                    @Override
                    protected void onPostExecute(Integer result) {
                        Toast.makeText(ActivityVirtualHost.this, 
                            getString(R.string.msg_hosts_cleared, result), Toast.LENGTH_SHORT).show();
                        refreshList();
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            })
            .setNegativeButton(R.string.title_cancel, null)
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    @Override
    protected void onDestroy() {
        running = false;
        downloader.destroy();
        super.onDestroy();
    }

    /**
     * RecyclerView Adapter for VirtualHost
     */
    private class VirtualHostAdapter extends RecyclerView.Adapter<VirtualHostAdapter.ViewHolder> {
        private Context context;
        private List<VirtualHost> data;

        public VirtualHostAdapter(Context context) {
            this.context = context;
            this.data = new java.util.ArrayList<>();
        }

        public void setData(List<VirtualHost> data) {
            this.data = data;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_virtual_host, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VirtualHost vh = data.get(position);
            holder.bind(vh);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private TextView tvDomain;
            private TextView tvIp;
            private TextView tvSource;
            private CheckBox cbEnabled;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvDomain = itemView.findViewById(R.id.tvDomain);
                tvIp = itemView.findViewById(R.id.tvIp);
                tvSource = itemView.findViewById(R.id.tvSource);
                cbEnabled = itemView.findViewById(R.id.cbEnabled);
            }

            public void bind(VirtualHost vh) {
                tvDomain.setText(vh.getDomain());
                tvIp.setText(vh.getIpAddress());
                cbEnabled.setChecked(vh.isEnabled());

                // Set source text
                String sourceText;
                switch (vh.getSource()) {
                    case VirtualHost.SOURCE_USER:
                        sourceText = context.getString(R.string.source_user);
                        break;
                    case VirtualHost.SOURCE_GITHUB:
                        sourceText = context.getString(R.string.source_github);
                        break;
                    default:
                        sourceText = context.getString(R.string.source_system);
                }
                tvSource.setText(sourceText);

                // Toggle enabled
                cbEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    vh.setEnabled(isChecked);
                    vh.update(context);
                });

                // Long click to edit/delete
                itemView.setOnClickListener(v -> showEditHostDialog(vh));
                itemView.setOnLongClickListener(v -> {
                    showDeleteConfirmDialog(vh);
                    return true;
                });
            }
        }
    }
}
