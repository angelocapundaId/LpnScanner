package com.empresa.lpnscanner;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class LpnListAdapter extends RecyclerView.Adapter<LpnListAdapter.VH> {

    public interface Callbacks {
        void onDelete(int position, LpnItem item);
    }

    private final List<LpnItem> data;
    private final Callbacks callbacks; // pode ser null

    public LpnListAdapter(List<LpnItem> data, Callbacks callbacks) {
        this.data = data;
        this.callbacks = callbacks;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lpn, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LpnItem it = data.get(position);
        h.tvLpn.setText(it.lpn);      // <<< usa campo público
        h.tvTime.setText(it.time);    // <<< usa campo público

        if (h.btnDelete != null && callbacks != null) {
            h.btnDelete.setOnClickListener(v ->
                    callbacks.onDelete(h.getBindingAdapterPosition(), it));
        }
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvLpn, tvTime;
        ImageButton btnDelete; // opcional no layout
        VH(@NonNull View itemView) {
            super(itemView);
            tvLpn = itemView.findViewById(R.id.tvLpn);
            tvTime = itemView.findViewById(R.id.tvTime);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
