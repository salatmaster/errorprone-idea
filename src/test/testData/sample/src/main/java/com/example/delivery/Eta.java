package com.example.delivery;

import java.time.Duration;

/** How long a parcel takes to reach an address. */
public interface Eta {
  Duration estimate(Address to);
}
