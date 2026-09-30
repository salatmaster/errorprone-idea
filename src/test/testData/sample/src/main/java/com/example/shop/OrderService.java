package com.example.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps the orders and answers questions about them. */
public class OrderService {
  private final List<Order> orders = new ArrayList<>();

  public List<Order> ordersOf(Customer customer) {
    if (customer == null) {
      return Collections.emptyList();
    }
    List<Order> result = new ArrayList<>();
    for (Order order : orders) {
      if (order.belongsTo(customer)) {
        result.add(order);
      }
    }
    return result;
  }

  public void importAll(ArrayList<Order> incoming) {
    List<Long> seen = new ArrayList<>();
    for (Order order : incoming) {
      seen.add(order.id());
      orders.add(order);
    }
  }
}
