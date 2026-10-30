package com.suntek.checkoutapplication;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DecimalFormat;
import java.util.List;

public class OrderAdapter extends RecyclerView.Adapter<OrderAdapter.OrderViewHolder> {

    /** Notifies the screen about +/- taps so totals, counts and the item map stay in sync. */
    public interface OnQuantityChangeListener {
        void onIncrease(OrderItem item);

        void onDecrease(OrderItem item);
    }

    private List<OrderItem> orderItems;
    private OnQuantityChangeListener listener;
    private DecimalFormat decimalFormat = new DecimalFormat("$#,##0.00");

    public OrderAdapter(List<OrderItem> orderItems, OnQuantityChangeListener listener) {
        this.orderItems = orderItems;
        this.listener = listener;
    }

    @NonNull
    @Override
    public OrderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_order, parent, false);
        return new OrderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull OrderViewHolder holder, int position) {
        OrderItem item = orderItems.get(position);
        Product product = item.getProduct();
        holder.productName.setText(product.getName());
        holder.quantity.setText(String.valueOf(item.getQuantity()));
        holder.unitPrice.setText(decimalFormat.format(product.getPrice()));
        holder.totalPrice.setText(decimalFormat.format(item.getTotalPrice()));

        holder.btnMinus.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) {
                listener.onDecrease(orderItems.get(pos));
            }
        });
        holder.btnPlus.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) {
                listener.onIncrease(orderItems.get(pos));
            }
        });
    }

    @Override
    public int getItemCount() {
        return orderItems.size();
    }

    static class OrderViewHolder extends RecyclerView.ViewHolder {
        TextView productName;
        TextView quantity;
        TextView unitPrice;
        TextView totalPrice;
        TextView btnMinus;
        TextView btnPlus;

        public OrderViewHolder(@NonNull View itemView) {
            super(itemView);
            productName = itemView.findViewById(R.id.tv_order_item_name);
            quantity = itemView.findViewById(R.id.tv_order_item_quantity);
            unitPrice = itemView.findViewById(R.id.tv_order_item_price);
            totalPrice = itemView.findViewById(R.id.tv_order_item_total);
            btnMinus = itemView.findViewById(R.id.btn_qty_minus);
            btnPlus = itemView.findViewById(R.id.btn_qty_plus);
        }
    }
}
