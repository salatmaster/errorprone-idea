package com.example.delivery;

import java.util.ArrayList;
import java.util.List;

/** Sends text messages through the operator's gateway. */
public class SmsNotifier implements Notifier {
  private final List<String> outbox = new ArrayList<>();

  @Override
  public void send(String phone, String text) {
    outbox.add(phone + ": " + text);
  }

  public List<String> outbox() {
    return outbox;
  }
}
