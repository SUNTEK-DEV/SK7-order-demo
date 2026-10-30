package com.suntek.checkoutapplication;

public class Product {
    private String id;
    private String name;
    private double price;
    private int imageResourceId;
    private String category;

    public Product(String id, String name, double price, int imageResourceId) {
        this(id, name, price, imageResourceId, "Other");
    }

    public Product(String id, String name, double price, int imageResourceId, String category) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.imageResourceId = imageResourceId;
        this.category = category;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getPrice() {
        return price;
    }

    public int getImageResourceId() {
        return imageResourceId;
    }

    public String getCategory() {
        return category;
    }
}
