package com.example.delivery;

import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Who is on shift today, by the zone they cover. */
public class CourierRoster {
  private final Map<Zone, Courier> onShift = new EnumMap<>(Zone.class);
  private final Map<Zone, Integer> parcels = new EnumMap<>(Zone.class);

  public void startShift(Zone zone, Courier courier) {
    onShift.put(zone, courier);
    parcels.put(zone, 0);
  }

  public @Nullable Courier courierFor(Zone zone) {
    return onShift.get(zone);
  }

  public String phoneFor(Zone zone) {
    return onShift.get(zone).phone();
  }

  public int parcelsFor(Zone zone) {
    return parcels.get(zone);
  }
}
