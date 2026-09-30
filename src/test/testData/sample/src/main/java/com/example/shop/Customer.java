package com.example.shop;

/** Someone who places orders. */
public class Customer {
  public static final String[] TIERS = {"basic", "silver", "gold"};

  private final String name;
  private final String email;
  private int loginCount;

  /**
   * Creates a customer.
   *
   * @param name the display name
   * @param mail the address receipts are sent to
   */
  public Customer(String name, String Email) {
    this.name = name;
    this.email = Email;
  }

  public String name() {
    return name;
  }

  public String email() {
    return email;
  }

  public void recordLogin() {
    loginCount++;
  }

  private String maskedEmail() {
    return email.charAt(0) + "***" + email.substring(email.indexOf('@'));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Customer other && email.equals(other.email);
  }

  @Override
  public int hashCode() {
    return email.hashCode();
  }

  public String toString() {
    return name + " <" + email + ">";
  }
}
