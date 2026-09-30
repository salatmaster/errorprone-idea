package com.example.shop;

/** One product in an order: how many, at what unit price. */
public record OrderLine(String sku, int quantity, int unitCents) {}
