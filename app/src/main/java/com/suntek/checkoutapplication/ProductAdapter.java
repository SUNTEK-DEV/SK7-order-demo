package com.suntek.checkoutapplication;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ProductViewHolder> {

    public static final String CATEGORY_ALL = "All";

    private final List<Product> allProducts = new ArrayList<>();
    private final List<Product> displayedProducts = new ArrayList<>();
    private OnProductClickListener listener;
    private DecimalFormat decimalFormat = new DecimalFormat("$#,##0.00");

    public interface OnProductClickListener {
        void onProductClick(Product product);
    }

    public ProductAdapter(List<Product> productList, OnProductClickListener listener) {
        this.listener = listener;
        submitList(productList);
    }

    public void submitList(List<Product> products) {
        allProducts.clear();
        if (products != null) {
            allProducts.addAll(products);
        }
        filter("", null);
    }

    /**
     * Show products whose name or barcode matches query, within category
     * (null or CATEGORY_ALL means no category restriction).
     */
    public void filter(String query, String category) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean allCategories = category == null || ProductAdapter.CATEGORY_ALL.equals(category);
        displayedProducts.clear();
        for (Product product : allProducts) {
            if (!allCategories && !category.equals(product.getCategory())) {
                continue;
            }
            if (q.isEmpty()
                    || product.getName().toLowerCase(Locale.ROOT).contains(q)
                    || product.getId().toLowerCase(Locale.ROOT).contains(q)) {
                displayedProducts.add(product);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ProductViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_product, parent, false);
        return new ProductViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProductViewHolder holder, int position) {
        Product product = displayedProducts.get(position);
        holder.productImage.setImageResource(product.getImageResourceId());
        holder.productName.setText(product.getName());
        holder.productPrice.setText(decimalFormat.format(product.getPrice()));
        holder.itemView.setOnClickListener(v -> listener.onProductClick(product));
    }

    @Override
    public int getItemCount() {
        return displayedProducts.size();
    }

    static class ProductViewHolder extends RecyclerView.ViewHolder {
        ImageView productImage;
        TextView productName;
        TextView productPrice;

        public ProductViewHolder(@NonNull View itemView) {
            super(itemView);
            productImage = itemView.findViewById(R.id.iv_product_image);
            productName = itemView.findViewById(R.id.tv_product_name);
            productPrice = itemView.findViewById(R.id.tv_product_price);
        }
    }
}
