package com.example.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Puts paid orders on a courier's round. */
public class DeliveryPlanner {
  private final CourierRoster roster;
  private final Notifier notifier;
  private final Eta eta;
  private Courier lastCourier;
  private @Nullable List<Address> backlog;

  public DeliveryPlanner(CourierRoster roster, Notifier notifier, Eta eta, Courier firstCourier) {
    this.roster = roster;
    this.notifier = notifier;
    this.eta = eta;
    this.lastCourier = firstCourier;
  }

  /** The courier who takes a parcel to {@code address}, and the one to call about it later. */
  public Courier assign(Address address) {
    Courier courier = roster.courierFor(address.zone());
    lastCourier = courier;
    return courier;
  }

  public Courier lastCourier() {
    return lastCourier;
  }

  /** What the customer pays for delivery, in euros. */
  public int fee(Address address) {
    return switch (address.zone()) {
      case CENTRE -> 0;
      case NORTH, SOUTH -> 5;
    };
  }

  /** Texts the customer once the parcel leaves the depot. */
  public void dispatch(Address address, @Nullable String phone) {
    Duration inTransit = eta.estimate(address);
    notifier.send(phone, "Your parcel arrives in " + inTransit.toMinutes() + " minutes");
  }

  /** Keeps an address for tomorrow's round. */
  public void postpone(Address address) {
    if (backlog == null) {
      backlog = new ArrayList<>();
    }
    backlog.add(address);
  }

  public boolean isPostponed(Address address) {
    for (Address waiting : backlog) {
      if (waiting.label().equals(address.label())) {
        return true;
      }
    }
    return false;
  }
}
