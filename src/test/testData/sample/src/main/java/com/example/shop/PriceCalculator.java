package com.example.shop;

import java.util.List;
import java.util.Random;

/** Turns order lines into prices. */
public class PriceCalculator {
  private static final double LOYALTY_FACTOR = 0.9500000000000000001;

  private final Random random = new Random();

  public long totalCents(List<OrderLine> lines) {
    long total = 0;
    for (OrderLine line : lines) {
      long lineTotal = line.quantity() * line.unitCents();
      total += lineTotal;
    }
    return total;
  }

  public int applyCoupon(int cents, short percentOff) {
    short discount = 0;
    discount += cents * percentOff / 100;
    return cents - discount;
  }

  public int pickLuckyBucket(int buckets) {
    return Math.abs(random.nextInt()) % buckets;
  }

  public boolean isFreeShipping(long cents, boolean member) {
    return cents > 5000 | member;
  }

  public double loyaltyPrice(long cents) {
    return cents * LOYALTY_FACTOR;
  }
}
