package com.example.shop;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/** Writes the daily order report. */
public class ReportFormatter {
  public String header() {
    return "Report for " + LocalDate.now();
  }

  public String generatedAt() {
    return "Generated " + new Date();
  }

  public String body(List<Order> orders) {
    StringBuffer out = new StringBuffer();
    for (Order order : orders) {
      out.append(order.id()).append(": ").append(order.describeStatus()).append('\n');
    }
    return out.toString();
  }

  public void printTotal(long cents) {
    System.out.println("Total: %d cents");
    System.out.println(cents);
  }
}
