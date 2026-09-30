package com.example.delivery;

import org.jspecify.annotations.Nullable;

/** Tells the customer where their parcel is. */
public interface Notifier {
  /** Sends {@code text} to {@code phone}, or nothing when the customer left no number. */
  void send(@Nullable String phone, String text);
}
