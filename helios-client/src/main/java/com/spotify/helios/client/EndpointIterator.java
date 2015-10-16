package com.spotify.helios.client;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class EndpointIterator implements Iterator<Integer> {

  private int cursor;
  private final int end;

  public EndpointIterator(int start, int end) {
    this.cursor = start;
    this.end = end;
  }

  public boolean hasNext() {
    return this.cursor < end;
  }

  public Integer next() {
    if (this.hasNext()) {
      int current = cursor;
      cursor ++;
      return current;
    }
    throw new NoSuchElementException();
  }

  public void remove() {
    throw new UnsupportedOperationException();
  }
}
