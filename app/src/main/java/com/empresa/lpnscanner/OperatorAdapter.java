package com.empresa.lpnscanner;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class OperatorAdapter extends RecyclerView.Adapter<OperatorAdapter.Holder> {

    public interface Callbacks {
        void onEditClicked(@NonNull Operator operator);
        void onRemoveClicked(@NonNull Operator operator);
    }

    private final List<Operator> items;
    private final Callbacks callbacks;

    public OperatorAdapter(List<Operator> items, Callbacks callbacks) {
        this.items = items;
        this.callbacks = callbacks;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_operator, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Operator op = items.get(position);
        h.tvOp.setText(op.id + " • " + op.name + (op.active ? " (ativo)" : " (inativo)"));

        h.btnEdit.setOnClickListener(v -> {
            if (callbacks != null) callbacks.onEditClicked(op);
        });

        h.btnDel.setOnClickListener(v -> {
            if (callbacks != null) callbacks.onRemoveClicked(op);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        TextView tvOp;
        ImageButton btnEdit;
        ImageButton btnDel;

        Holder(@NonNull View itemView) {
            super(itemView);
            tvOp = itemView.findViewById(R.id.tvOp);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDel = itemView.findViewById(R.id.btnDel);
        }
    }
}