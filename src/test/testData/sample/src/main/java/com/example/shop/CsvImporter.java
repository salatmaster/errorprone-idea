package com.example.shop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Reads order lines from the CSV files the warehouse exports. */
public class CsvImporter {
  public List<OrderLine> read(InputStream in) {
    List<OrderLine> lines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
      String row;
      while ((row = reader.readLine()) != null) {
        String[] cells = row.split(",");
        String sku = cells[0].trim().toUpperCase();
        lines.add(new OrderLine(sku, parseQuantity(cells[1]), Integer.parseInt(cells[2])));
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return lines;
  }

  public int parseQuantity(String text) {
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Not a quantity: " + text);
    }
  }
}
