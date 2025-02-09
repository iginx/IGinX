/*
 * IGinX - the polystore system with high performance
 * Copyright (C) Tsinghua University
 * TSIGinX@gmail.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package cn.edu.tsinghua.iginx.filesystem.struct.legacy.parquet.db.util.iterator;

import cn.edu.tsinghua.iginx.filesystem.struct.legacy.parquet.util.exception.StorageException;
import java.util.*;

public class RowUnionScanner<K extends Comparable<K>, F, V> implements Scanner<K, Scanner<F, V>> {

  private final PriorityQueue<Map.Entry<Scanner<K, Scanner<F, V>>, Long>> queue;

  public RowUnionScanner(Iterable<Scanner<K, Scanner<F, V>>> scanners) throws StorageException {
    Comparator<Map.Entry<Scanner<K, Scanner<F, V>>, Long>> comparing =
        Comparator.comparing(e -> e.getKey().key());
    this.queue = new PriorityQueue<>(comparing.thenComparing(Map.Entry::getValue));

    StorageException exception = null;
    long i = 0;
    for (Scanner<K, Scanner<F, V>> scanner : scanners) {
      if (scanner.iterate()) {
        queue.add(new AbstractMap.SimpleImmutableEntry<>(scanner, i));
        i++;
      } else {
        try {
          scanner.close();
        } catch (StorageException e) {
          if (exception == null) {
            exception = e;
          } else {
            exception.addSuppressed(e);
          }
        }
      }
    }
    if (exception != null) {
      throw exception;
    }
  }

  private Scanner<F, V> currentRow = null;

  private K currentKey = null;

  @Override
  public K key() throws NoSuchElementException {
    if (currentKey == null) {
      throw new NoSuchElementException();
    }
    return currentKey;
  }

  @Override
  public Scanner<F, V> value() throws NoSuchElementException {
    if (currentRow == null) {
      throw new NoSuchElementException();
    }

    return currentRow;
  }

  @Override
  public boolean iterate() throws StorageException {
    Map<F, V> row = new HashMap<>();
    if (queue.isEmpty()) {
      currentRow = null;
      currentKey = null;
      return false;
    }
    currentKey = queue.peek().getKey().key();
    while (!queue.isEmpty() && currentKey.compareTo(queue.peek().getKey().key()) == 0) {
      Map.Entry<Scanner<K, Scanner<F, V>>, Long> entry = queue.poll();
      assert entry != null;
      Scanner<F, V> scanner = entry.getKey().value();
      while (scanner.iterate()) {
        row.putIfAbsent(scanner.key(), scanner.value());
      }
      if (entry.getKey().iterate()) {
        queue.add(entry);
      }
    }
    currentRow =
        new Scanner<F, V>() {
          private final Iterator<Map.Entry<F, V>> iterator = row.entrySet().iterator();

          private F key;

          private V value;

          @Override
          public F key() throws NoSuchElementException {
            if (key == null) {
              throw new NoSuchElementException();
            }
            return key;
          }

          @Override
          public V value() throws NoSuchElementException {
            if (value == null) {
              throw new NoSuchElementException();
            }
            return value;
          }

          @Override
          public boolean iterate() throws StorageException {
            if (!iterator.hasNext()) {
              key = null;
              value = null;
              return false;
            }
            Map.Entry<F, V> entry = iterator.next();
            key = entry.getKey();
            value = entry.getValue();
            return true;
          }

          @Override
          public void close() throws StorageException {}
        };
    return true;
  }

  @Override
  public void close() throws StorageException {
    StorageException exception = null;
    for (Map.Entry<Scanner<K, Scanner<F, V>>, Long> entry : queue) {
      try {
        entry.getKey().close();
      } catch (StorageException e) {
        if (exception == null) {
          exception = e;
        } else {
          exception.addSuppressed(e);
        }
      }
    }
    if (exception != null) {
      throw exception;
    }
  }
}
