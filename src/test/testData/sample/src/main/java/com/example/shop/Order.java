package com.example.shop;

import java.util.ArrayList;
import java.util.List;

/** An order a customer placed, and what happened to it since. */
public class Order {
  private final long id;
  private final Customer customer;
  private final List<OrderLine> lines = new ArrayList<>();
  private OrderStatus status = OrderStatus.NEW;

  public Order(long id, Customer customer) {
    this.id = id;
    this.customer = customer;
  }

  public long id() {
    return id;
  }

  public List<OrderLine> lines() {
    return lines;
  }

  public void add(OrderLine line) {
    lines.add(line);
  }

  public void markPaid() {
    status = OrderStatus.PAID;
  }

  public boolean belongsTo(Customer other) {
    return customer == other;
  }

  public boolean canBeCancelled(boolean paid, boolean shipped, boolean admin) {
    return !paid && !shipped || admin;
  }

  public String describeStatus() {
    String text = "unknown";
    switch (status) {
      case NEW:
        text = "waiting for payment";
      case PAID:
        text = "paid, packing";
        break;
      case SHIPPED:
        text = "on its way";
        break;
    }
    return text;
  }
}
