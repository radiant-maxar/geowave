/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.core.store.memory;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.locationtech.geowave.core.index.persist.PersistenceUtils;
import org.locationtech.geowave.core.store.adapter.InternalDataAdapter;
import org.locationtech.geowave.core.store.adapter.PersistentAdapterStore;

/**
 * This is a simple HashMap based in-memory implementation of the PersistentAdapterStore and can be
 * useful if it is undesirable to persist and query objects within another storage mechanism such as
 * an Accumulo table.
 */
public class MemoryPersistentAdapterStore implements PersistentAdapterStore, Serializable {
  /** */
  private static final long serialVersionUID = 1L;

  private Map<Short, InternalDataAdapter<?>> adapterMap;

  public MemoryPersistentAdapterStore() {
    adapterMap = Collections.synchronizedMap(new HashMap<Short, InternalDataAdapter<?>>());
  }

  public MemoryPersistentAdapterStore(final InternalDataAdapter<?>[] adapters) {
    adapterMap = Collections.synchronizedMap(new HashMap<Short, InternalDataAdapter<?>>());
    for (final InternalDataAdapter<?> adapter : adapters) {
      adapterMap.put(adapter.getAdapterId(), adapter);
    }
  }

  @Override
  public void addAdapter(final InternalDataAdapter<?> InternalDataadapter) {
    adapterMap.put(InternalDataadapter.getAdapterId(), InternalDataadapter);
  }

  @Override
  public InternalDataAdapter<?> getAdapter(final Short internalAdapterId) {
    return adapterMap.get(internalAdapterId);
  }

  @Override
  public boolean adapterExists(final Short internalAdapterId) {
    return adapterMap.containsKey(internalAdapterId);
  }

  @Override
  public InternalDataAdapter<?>[] getAdapters() {
    return adapterMap.values().toArray(new InternalDataAdapter[adapterMap.size()]);
  }

  @Override
  public void removeAll() {
    adapterMap.clear();
  }

  private void writeObject(final java.io.ObjectOutputStream out) throws IOException {
    final int count = adapterMap.size();
    out.writeInt(count);
    for (final Map.Entry<Short, InternalDataAdapter<?>> entry : adapterMap.entrySet()) {
      out.writeObject(entry.getKey());
      final byte[] val = PersistenceUtils.toBinary(entry.getValue());
      out.writeObject(val);
    }
  }

  private void readObject(final java.io.ObjectInputStream in)
      throws IOException, ClassNotFoundException {
    final int count = in.readInt();
    adapterMap = Collections.synchronizedMap(new HashMap<Short, InternalDataAdapter<?>>());
    for (int i = 0; i < count; i++) {
      final Short id = (Short) in.readObject();
      final byte[] data = (byte[]) in.readObject();
      adapterMap.put(id, (InternalDataAdapter<?>) PersistenceUtils.fromBinary(data));
    }
  }

  @Override
  public void removeAdapter(final Short adapterId) {}
}
