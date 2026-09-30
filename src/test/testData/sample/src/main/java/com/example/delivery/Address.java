package com.example.delivery;

import org.jspecify.annotations.Nullable;

/** Where a parcel goes. A house has no apartment, and a new street may not be zoned yet. */
public final class Address {
  private final String street;
  private final @Nullable String apartment;
  private final String city;
  private final @Nullable Zone zone;
  private String instructions;

  public Address(String street, @Nullable String apartment, String city, @Nullable Zone zone) {
    this.street = street;
    this.apartment = apartment;
    this.city = city;
    this.zone = zone;
  }

  public String city() {
    return city;
  }

  public @Nullable Zone zone() {
    return zone;
  }

  public String instructions() {
    return instructions;
  }

  public void leaveInstructions(String instructions) {
    this.instructions = instructions;
  }

  /** One line for the parcel label. */
  public String label() {
    return street + ", apt. " + apartment.trim() + ", " + city;
  }
}
