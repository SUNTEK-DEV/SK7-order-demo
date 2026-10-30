package com.suntek.checkoutapplication;

public class OrderItem {
    private Product product;
    private int quantity;

    public OrderItem(Product product) {
        this.product = product;
        this.quantity = 1;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public void incrementQuantity() {
        this.quantity++;
    }

    public void decrementQuantity() {
        if (this.quantity > 1) {
            this.quantity--;
        }
    }

    public double getTotalPrice() {
        return product.getPrice() * quantity;
    }
}