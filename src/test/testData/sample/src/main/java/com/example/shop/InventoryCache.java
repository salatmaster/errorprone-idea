package com.example.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/** Stock levels, shared by every request thread. */
public class InventoryCache {
  private static Map<String, Integer> stock;

  private final ReentrantLock lock = new ReentrantLock();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private Object monitor = new Object();
  private boolean refreshed;

  public Map<String, Integer> stock() {
    if (stock == null) {
      synchronized (InventoryCache.class) {
        if (stock == null) {
          stock = new HashMap<>();
        }
      }
    }
    return stock;
  }

  public void reserve(String sku, int quantity) {
    lock.lock();
    stock().merge(sku, -quantity, Integer::sum);
    try {
      refreshed = false;
    } finally {
      lock.unlock();
    }
  }

  public void awaitRefresh() throws InterruptedException {
    synchronized (monitor) {
      if (!refreshed) {
        monitor.wait();
      }
    }
  }

  public void refreshLater() {
    executor.submit(() -> refreshed = true);
  }
}
