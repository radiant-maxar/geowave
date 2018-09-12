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
package mil.nga.giat.geowave.analytic.mapreduce;

import mil.nga.giat.geowave.analytic.PropertyManagement;

import org.apache.hadoop.conf.Configuration;

public interface MapReduceJobRunner
{
	public int run(
			final Configuration config,
			final PropertyManagement runTimeProperties )
			throws Exception;
}
