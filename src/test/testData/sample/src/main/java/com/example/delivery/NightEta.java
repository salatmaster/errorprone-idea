package com.example.delivery;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Deliveries after dark: none go to a street that is not zoned yet. */
public class NightEta implements Eta {
  @Override
  public @Nullable Duration estimate(Address to) {
    if (to.zone() == null) {
      return null;
    }
    return Duration.ofMinutes(90);
  }
}
