package com.example.shop;

/** Where an order is in its life. */
public enum OrderStatus {
  NEW("New"),
  PAID("Paid"),
  SHIPPED("Shipped"),
  CANCELLED("Cancelled");

  private String label;

  OrderStatus(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public void relabel(String label) {
    this.label = label;
  }
}
