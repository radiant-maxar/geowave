/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.core.index.text;

import org.locationtech.geowave.core.index.MultiDimensionalIndexData;

/** Interface which defines the methods associated with a multi-dimensional text data range. */
public interface MultiDimensionalTextData extends MultiDimensionalIndexData<String> {
  /** @return an array of object QueryRange */
  @Override
  public TextData[] getDataPerDimension();
}
