/*
 * Copyright (C) 2014-2015 CS-SI (foss-contact@thor.si.c-s.fr)
 * Copyright (C) 2013-2015 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.s2tbx.dataio.s2.l1c;

import https.psd_13_sentinel2_eo_esa_int.psd.s2_pdi_level_1c_tile_metadata.Level1C_Tile;
import https.psd_13_sentinel2_eo_esa_int.psd.user_product_level_1c.Level1C_User_Product;
import org.esa.s2tbx.dataio.Utils;
import org.esa.s2tbx.dataio.s2.S2Config;
import org.esa.s2tbx.dataio.s2.S2Metadata;
import org.esa.s2tbx.dataio.s2.S2SpatialResolution;
import org.esa.s2tbx.dataio.s2.filepatterns.S2DatastripDirFilename;
import org.esa.s2tbx.dataio.s2.filepatterns.S2DatastripFilename;
import org.esa.s2tbx.dataio.s2.ortho.filepatterns.S2OrthoGranuleDirFilename;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.util.SystemUtils;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Represents the Sentinel-2 MSI L1C XML metadata header file.
 * <p>
 * Note: No data interpretation is done in this class, it is intended to serve the pure metadata content only.
 *
 * @author Norman Fomferra
 */
public class L1cMetadata extends S2Metadata {

    private static final String PSD_STRING = "13";
    private static final int DEFAULT_ANGLES_RESOLUTION = 5000;

    protected Logger logger = SystemUtils.LOG;

    public static L1cMetadata parseHeader(File file, String granuleName, S2Config config, String epsg) throws JDOMException, IOException, JAXBException {
        try (FileInputStream stream = new FileInputStream(file)) {
            return new L1cMetadata(stream, file, file.getParent(), granuleName, config, epsg);
        }
    }

    private L1cMetadata(InputStream stream, File file, String parent, String granuleName, S2Config config, String epsg) throws JDOMException, JAXBException, FileNotFoundException {
        super(config, L1cMetadataProc.getJaxbContext(), PSD_STRING);

        try {
            Object userProduct = updateAndUnmarshal(stream);

            if (userProduct instanceof Level1C_User_Product) {
                initProduct(file, parent, granuleName, userProduct, epsg);
            } else {
                initTile(file, userProduct);
            }
        } catch (UnmarshalException | JDOMException e) {
            logger.severe(String.format("Product is not conform to PSD: %s", e.getMessage()));
            throw e;
        } catch (JAXBException | IOException e) {
            logger.severe(Utils.getStackTrace(e));
        }
    }

    private void initProduct(File file, String parent, String granuleName, Object casted, String epsg) throws IOException, JAXBException, JDOMException {
        Level1C_User_Product product = (Level1C_User_Product) casted;
        setProductCharacteristics(L1cMetadataProc.getProductOrganization(product));

        Collection<String> tileNames;

        if (granuleName == null) {
            tileNames = L1cMetadataProc.getTiles(product);
        } else {
            tileNames = Collections.singletonList(granuleName);
        }

        List<File> fullTileNamesList = new ArrayList<>();

        resetTileList();

        for (String tileName : tileNames) {

            S2OrthoGranuleDirFilename aGranuleDir = S2OrthoGranuleDirFilename.create(tileName);

            if (aGranuleDir != null) {
                String theName = aGranuleDir.getMetadataFilename().name;

                File nestedGranuleMetadata = new File(parent, "GRANULE" + File.separator + tileName + File.separator + theName);
                if (nestedGranuleMetadata.exists()) {
                    fullTileNamesList.add(nestedGranuleMetadata);
                } else {
                    String errorMessage = "Corrupted product: the file for the granule " + tileName + " is missing";
                    logger.log(Level.WARNING, errorMessage);
                }
            }
        }

        for (File aGranuleMetadataFile : fullTileNamesList) {
            try (FileInputStream granuleStream = new FileInputStream(aGranuleMetadataFile)) {
                Level1C_Tile aTile = (Level1C_Tile) updateAndUnmarshal(granuleStream);
                Map<S2SpatialResolution, TileGeometry> geoms = L1cMetadataProc.getTileGeometries(aTile);

                Tile tile = new Tile(aTile.getGeneral_Info().getTILE_ID().getValue());
                tile.setHorizontalCsCode(aTile.getGeometric_Info().getTile_Geocoding().getHORIZONTAL_CS_CODE());
                tile.setHorizontalCsName(aTile.getGeometric_Info().getTile_Geocoding().getHORIZONTAL_CS_NAME());

                if (!tile.getHorizontalCsCode().equals(epsg)) {
                    // skip tiles that are not in the desired UTM zone
                    logger.info(String.format("Skipping tile %s because it has crs %s instead of requested %s", aGranuleMetadataFile.getName(), tile.getHorizontalCsCode(), epsg));
                    continue;
                }

                tile.setTileGeometries(geoms);
                try {
                    tile.setAnglesResolution((int) aTile.getGeometric_Info().getTile_Angles().getSun_Angles_Grid().getAzimuth().getCOL_STEP().getValue());
                } catch (Exception e) {
                    logger.warning("Angles resolution cannot be obtained");
                    tile.setAnglesResolution(DEFAULT_ANGLES_RESOLUTION);
                }

                tile.setSunAnglesGrid(L1cMetadataProc.getSunGrid(aTile));
                tile.setViewingIncidenceAnglesGrids(L1cMetadataProc.getAnglesGrid(aTile));

                tile.setMaskFilenames(L1cMetadataProc.getMasks(aTile, aGranuleMetadataFile));

                addTileToList(tile);
            }
        }

        S2DatastripFilename stripName = L1cMetadataProc.getDatastrip(product);
        S2DatastripDirFilename dirStripName = L1cMetadataProc.getDatastripDir(product);

        File dataStripMetadata = new File(parent, "DATASTRIP" + File.separator + dirStripName.name + File.separator + stripName.name);

        MetadataElement userProduct = parseAll(new SAXBuilder().build(file).getRootElement());
        MetadataElement dataStrip = parseAll(new SAXBuilder().build(dataStripMetadata).getRootElement());
        getMetadataElements().add(userProduct);
        getMetadataElements().add(dataStrip);
        MetadataElement granulesMetaData = new MetadataElement("Granules");

        for (File aGranuleMetadataFile : fullTileNamesList) {
            MetadataElement aGranule = parseAll(new SAXBuilder().build(aGranuleMetadataFile).getRootElement());

            MetadataElement generalInfo = aGranule.getElement("General_Info");
            if (generalInfo != null) {
                MetadataAttribute tileIdAttr = generalInfo.getAttribute("TILE_ID");
                if (tileIdAttr != null) {
                    String newName = tileIdAttr.getData().toString();
                    if (newName.length() > 56)
                        aGranule.setName("Level-1C_Tile_" + newName.substring(50, 55));
                }
            }
            granulesMetaData.addElement(aGranule);
        }

        getMetadataElements().add(granulesMetaData);
    }

    private void initTile(File file, Object casted) throws IOException, JAXBException, JDOMException {
        Level1C_Tile aTile = (Level1C_Tile) casted;
        resetTileList();

        Map<S2SpatialResolution, TileGeometry> geoms = L1cMetadataProc.getTileGeometries(aTile);

        Tile tile = new Tile(aTile.getGeneral_Info().getTILE_ID().getValue());
        tile.setHorizontalCsCode(aTile.getGeometric_Info().getTile_Geocoding().getHORIZONTAL_CS_CODE());
        tile.setHorizontalCsName(aTile.getGeometric_Info().getTile_Geocoding().getHORIZONTAL_CS_NAME());

        tile.setTileGeometries(geoms);
        tile.setSunAnglesGrid(L1cMetadataProc.getSunGrid(aTile));
        tile.setViewingIncidenceAnglesGrids(L1cMetadataProc.getAnglesGrid(aTile));

        L1cMetadataProc.getMasks(aTile, file);
        tile.setMaskFilenames(L1cMetadataProc.getMasks(aTile, file));

        addTileToList(tile);
    }
}
