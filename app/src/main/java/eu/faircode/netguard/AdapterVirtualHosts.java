package eu.faircode.netguard;

import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

public class AdapterVirtualHosts extends CursorAdapter {
    private final int colId;
    private final int colHostname;
    private final int colIp;
    private final int colEnabled;
    private final int colSource;

    public AdapterVirtualHosts(Context context, Cursor cursor) {
        super(context, cursor, 0);
        colId = cursor.getColumnIndex("ID");
        colHostname = cursor.getColumnIndex("hostname");
        colIp = cursor.getColumnIndex("ip");
        colEnabled = cursor.getColumnIndex("enabled");
        colSource = cursor.getColumnIndex("source");
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return LayoutInflater.from(context).inflate(R.layout.virtual_host_item, parent, false);
    }

    @Override
    public void bindView(View view, final Context context, Cursor cursor) {
        final long id = cursor.getLong(colId);
        String hostname = cursor.getString(colHostname);
        String ip = cursor.getString(colIp);
        boolean enabled = cursor.getInt(colEnabled) > 0;
        String source = cursor.getString(colSource);

        TextView tvHostname = view.findViewById(R.id.tvHostname);
        TextView tvIp = view.findViewById(R.id.tvIp);
        TextView tvSource = view.findViewById(R.id.tvSource);
        SwitchCompat swEnabled = view.findViewById(R.id.swHostEnabled);
        ImageView ivDelete = view.findViewById(R.id.ivDelete);

        tvHostname.setText(hostname);
        tvIp.setText(ip);
        tvSource.setText(source);

        swEnabled.setOnCheckedChangeListener(null);
        swEnabled.setChecked(enabled);
        swEnabled.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                DatabaseHelper.getInstance(context).setVirtualHostEnabled(id, isChecked);
                ServiceSinkhole.reload("virtual hosts", context, false);
            }
        });

        ivDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DatabaseHelper.getInstance(context).deleteVirtualHost(id);
                if (context instanceof ActivityVirtualHosts) {
                    changeCursor(DatabaseHelper.getInstance(context).getVirtualHosts());
                }
                ServiceSinkhole.reload("virtual hosts", context, false);
            }
        });
    }
}
