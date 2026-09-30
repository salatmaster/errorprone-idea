package com.example.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An amount of money in one currency, kept to the cent. */
public class Money {
  public static final Money ZERO = new Money(BigDecimal.ZERO, "EUR");
  static final BigDecimal VAT_RATE = new BigDecimal(0.2);

  private final BigDecimal amount;
  private final String currency;

  public Money(BigDecimal amount, String currency) {
    this.amount = amount.setScale(2, RoundingMode.HALF_EVEN);
    this.currency = currency;
  }

  public Money plus(Money other) {
    return new Money(amount.add(other.amount), currency);
  }

  public Money withVat() {
    return new Money(amount.multiply(BigDecimal.ONE.add(VAT_RATE)), currency);
  }

  public boolean isZero() {
    return amount.equals(BigDecimal.ZERO);
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Money money = (Money) o;
    return amount.compareTo(money.amount) == 0 && currency.equals(money.currency);
  }

  @Override
  public int hashCode() {
    return Objects.hash(amount, currency);
  }

  @Override
  public String toString() {
    return amount + " " + currency;
  }
}
