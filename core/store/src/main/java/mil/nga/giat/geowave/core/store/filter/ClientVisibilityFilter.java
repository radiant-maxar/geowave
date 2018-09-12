/*******************************************************************************
 * Copyright (c) 2013-2018 Contributors to the Eclipse Foundation
 *   
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Apache License,
 *  Version 2.0 which accompanies this distribution and is available at
 *  http://www.apache.org/licenses/LICENSE-2.0.txt
 ******************************************************************************/
package mil.nga.giat.geowave.core.store.filter;

import java.util.Set;

import com.google.common.base.Predicate;

import mil.nga.giat.geowave.core.index.StringUtils;
import mil.nga.giat.geowave.core.store.entities.GeoWaveRow;
import mil.nga.giat.geowave.core.store.entities.GeoWaveValue;
import mil.nga.giat.geowave.core.store.util.VisibilityExpression;

/**
 * Provides a visibility filter for UNMERGED rows. The filter only operates on
 * the first {@link GeoWaveValue} of each row and must be applied prior to row
 * merging.
 */
public class ClientVisibilityFilter implements
		Predicate<GeoWaveRow>
{
	private final Set<String> auths;

	public ClientVisibilityFilter(
			Set<String> auths ) {
		this.auths = auths;
	}

	@Override
	public boolean apply(
			GeoWaveRow input ) {
		String visibility = "";
		GeoWaveValue[] fieldValues = input.getFieldValues();
		if (fieldValues.length > 0 && fieldValues[0].getVisibility() != null) {
			visibility = StringUtils.stringFromBinary(input.getFieldValues()[0].getVisibility());
		}
		return VisibilityExpression.evaluate(
				visibility,
				auths);
	}

}
