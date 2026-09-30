package com.example.tools;

import java.util.LinkedList;
import java.util.List;

/** Command-line tool that rebuilds the search index. */
public class ReindexTool {
  public static void main(String[] args) {
    List<String> queue = new LinkedList<>();
    for (String arg : args) {
      queue.add(arg);
    }
    System.out.println("Reindexing " + queue.size() + " collections");
  }
}
