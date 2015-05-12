/*
 *
 *  * Copyright (C) 2015 CS SI
 *  *
 *  * This program is free software; you can redistribute it and/or modify it
 *  * under the terms of the GNU General Public License as published by the Free
 *  * Software Foundation; either version 3 of the License, or (at your option)
 *  * any later version.
 *  * This program is distributed in the hope that it will be useful, but WITHOUT
 *  * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 *  * more details.
 *  *
 *  * You should have received a copy of the GNU General Public License along
 *  * with this program; if not, see http://www.gnu.org/licenses/
 *
 */

package org.esa.s2tbx.dataio.s2.l2a;

/**
 * @author Norman Fomferra
 */
public class S2L2AWavebandInfo {
    final int bandId;
    final String bandName;
    final S2SpatialResolution resolution;
    final double wavelength;
    final double bandwidth;

    S2L2AWavebandInfo(int bandId, String bandName, S2SpatialResolution resolution, double wavelength,
                      double bandwidth) {
        this.bandId = bandId;
        this.bandName = bandName;
        this.wavelength = wavelength;
        this.bandwidth = bandwidth;
        this.resolution = resolution;
    }
}