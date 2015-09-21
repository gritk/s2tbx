
package org.esa.s2tbx.dataio.s2.ortho.plugins;

import org.esa.s2tbx.dataio.s2.ortho.S2OrthoProductReaderPlugIn;

/**
 * Reader plugin for S2 MSI L1C over WGS84 / UTM Zone 28 N
 */
public class Sentinel2L1CProduct_Multi_UTM28N_ReaderPlugIn extends S2OrthoProductReaderPlugIn {

    @Override
    public String getEPSG()
    {
        return "EPSG:32628";
    }

}