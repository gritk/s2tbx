/*
 *
 * Copyright (C) 2013-2014 Brockmann Consult GmbH (info@brockmann-consult.de)
 * Copyright (C) 2014-2015 CS SI
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *  This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 *
 */

package org.esa.s2tbx.dataio.jp2.boxes;

import org.esa.s2tbx.dataio.jp2.Box;
import org.esa.s2tbx.dataio.jp2.BoxReader;
import org.esa.s2tbx.dataio.jp2.BoxType;

import java.io.IOException;

/**
 * @author Norman Fomferra
 */
public class BitsPerComponentBox extends Box {

    public BitsPerComponentBox(BoxType type, long position, long length, int dataOffset) {
        super(type, position, length, dataOffset);
    }

    @Override
    public void readFrom(BoxReader reader) throws IOException {


    }
}