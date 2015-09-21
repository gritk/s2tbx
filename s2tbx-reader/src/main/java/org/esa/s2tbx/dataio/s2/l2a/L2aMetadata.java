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

package org.esa.s2tbx.dataio.s2.l2a;

import https.psd_12_sentinel2_eo_esa_int.psd.s2_pdi_level_2a_tile_metadata.Level2A_Tile;
import https.psd_12_sentinel2_eo_esa_int.psd.user_product_level_2a.Level2A_User_Product;
import org.esa.s2tbx.dataio.Utils;
import org.esa.s2tbx.dataio.s2.S2Config;
import org.esa.s2tbx.dataio.s2.S2Metadata;
import org.esa.s2tbx.dataio.s2.S2SpatialResolution;
import org.esa.s2tbx.dataio.s2.filepatterns.S2DatastripDirFilename;
import org.esa.s2tbx.dataio.s2.filepatterns.S2DatastripFilename;
import org.esa.s2tbx.dataio.s2.filepatterns.S2GranuleDirFilename;
import org.esa.s2tbx.dataio.s2.ortho.filepatterns.S2OrthoGranuleDirFilename;
import org.esa.s2tbx.dataio.s2.ortho.Counter;
import org.esa.s2tbx.dataio.s2.ortho.S2OrthoMetadata;
import org.esa.snap.framework.datamodel.MetadataElement;
import org.esa.snap.util.SystemUtils;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Represents the Sentinel-2 MSI L1C XML metadata header file.
 * <p>
 * Note: No data interpretation is done in this class, it is intended to serve the pure metadata content only.
 *
 * @author Norman Fomferra
 */
public class L2aMetadata extends S2OrthoMetadata {

    private static final String PSD_STRING = "12";

    protected Logger logger = SystemUtils.LOG;

    private Collection<ImageInfo> imageList;

    public static L2aMetadata parseHeader(File file, String granuleName, S2Config config, String epsg, S2SpatialResolution productResolution) throws JDOMException, IOException, JAXBException {
        return new L2aMetadata(new FileInputStream(file), file, file.getParent(), granuleName, config, epsg, productResolution);
    }

    public Collection<ImageInfo> getImageList() {
        return imageList;
    }

    private L2aMetadata(InputStream stream, File file, String parent, String granuleName, S2Config config, String epsg, S2SpatialResolution productResolution) throws JDOMException, JAXBException, FileNotFoundException {
        super(config, L2aMetadataProc.getJaxbContext(), PSD_STRING);

        try {
            Object userProductOrTile = updateAndUnmarshal(stream);

            if(userProductOrTile instanceof Level2A_User_Product) {
                initProduct(file, parent, granuleName, userProductOrTile, epsg, productResolution);
            } else {
                initTile(file, userProductOrTile);
            }
        } catch (JAXBException | JDOMException | IOException e) {
            logger.severe(Utils.getStackTrace(e));
        }
    }

    private void initProduct(File file, String parent, String granuleName, Object casted, String epsg, S2SpatialResolution productResolution) throws IOException, JAXBException, JDOMException {
        Level2A_User_Product product = (Level2A_User_Product) casted;
        setProductCharacteristics(L2aMetadataProc.getProductOrganization(product, productResolution));

        Collection<String> tileNames;

        if(granuleName == null ) {
            tileNames = L2aMetadataProc.getTiles(product);
        } else {
            tileNames = Collections.singletonList(granuleName);
        }

        List<File> fullTileNamesList = new ArrayList<>();

        resetTileList();

        imageList = L2aMetadataProc.getImages(product);

        resetTileList();

        for (String tileName : tileNames) {
            S2GranuleDirFilename aGranuleDir = S2OrthoGranuleDirFilename.create(tileName);
            if(aGranuleDir != null) {
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

        Map<String, Counter> counters = new HashMap<>();

        for (File aGranuleMetadataFile : fullTileNamesList) {
            FileInputStream granuleStream = new FileInputStream(aGranuleMetadataFile);
            Level2A_Tile aTile = (Level2A_Tile) updateAndUnmarshal(granuleStream);

            Map<Integer, TileGeometry> geoms = L2aMetadataProc.getTileGeometries(aTile);

            Tile tile = new Tile(aTile.getGeneral_Info().getTILE_ID_2A().getValue());
            tile.setHorizontalCsCode(aTile.getGeometric_Info().getTile_Geocoding().getHORIZONTAL_CS_CODE());
            tile.setHorizontalCsName(aTile.getGeometric_Info().getTile_Geocoding().getHORIZONTAL_CS_NAME());

            if (! tile.getHorizontalCsCode().equals(epsg)) {
                // skip tiles that are not in the desired UTM zone
                logger.info(String.format("Skipping tile %s because it has crs %s instead of requested %s", aGranuleMetadataFile.getName(), tile.getHorizontalCsCode(), epsg));
                continue;
            }

            // counting tiles by UTM zones
            String key = tile.getHorizontalCsCode();
            if (counters.containsKey(key)) {
                counters.get(key).increment();
            } else {
                counters.put(key, new Counter(key));
                counters.get(key).increment();
            }

            tile.setTileGeometry10M(geoms.get(S2SpatialResolution.R10M.resolution));
            tile.setTileGeometry20M(geoms.get(S2SpatialResolution.R20M.resolution));
            tile.setTileGeometry60M(geoms.get(S2SpatialResolution.R60M.resolution));

            tile.setSunAnglesGrid(L2aMetadataProc.getSunGrid(aTile));
            tile.setViewingIncidenceAnglesGrids(L2aMetadataProc.getAnglesGrid(aTile));

            tile.setMaskFilenames(L2aMetadataProc.getMasks(aTile, aGranuleMetadataFile));

            addTileToList(tile);
        }

        for(String key: counters.keySet())
        {
            List<S2Metadata.Tile> aUTMZone =
                    getTileList().stream().filter(i -> i.getHorizontalCsCode().equals(key)).collect(Collectors.toList());
            addTileListToAllTileList(key, aUTMZone);
        }

        S2DatastripFilename stripName = L2aMetadataProc.getDatastrip(product);
        S2DatastripDirFilename dirStripName = L2aMetadataProc.getDatastripDir(product);

        File dataStripMetadata = new File(parent, "DATASTRIP" + File.separator + dirStripName.name + File.separator + stripName.name);

        MetadataElement userProduct = parseAll(new SAXBuilder().build(file).getRootElement());
        MetadataElement dataStrip = parseAll(new SAXBuilder().build(dataStripMetadata).getRootElement());
        getMetadataElements().add(userProduct);
        getMetadataElements().add(dataStrip);
        MetadataElement granulesMetaData = new MetadataElement("Granules");

        for (File aGranuleMetadataFile : fullTileNamesList) {
            MetadataElement aGranule = parseAll(new SAXBuilder().build(aGranuleMetadataFile).getRootElement());
            granulesMetaData.addElement(aGranule);
        }

        getMetadataElements().add(granulesMetaData);
    }

    private void initTile(File file, Object casted) throws IOException, JAXBException, JDOMException {
        Level2A_Tile aTile = (Level2A_Tile) casted;
        resetTileList();

        Map<Integer, TileGeometry> geoms = L2aMetadataProc.getTileGeometries(aTile);

        Tile tile = new Tile(aTile.getGeneral_Info().getTILE_ID_2A().getValue());
        tile.setHorizontalCsCode(aTile.getGeometric_Info().getTile_Geocoding().getHORIZONTAL_CS_CODE());
        tile.setHorizontalCsName(aTile.getGeometric_Info().getTile_Geocoding().getHORIZONTAL_CS_NAME());

        tile.setTileGeometry10M(geoms.get(10));
        tile.setTileGeometry20M(geoms.get(20));
        tile.setTileGeometry60M(geoms.get(60));

        tile.setSunAnglesGrid(L2aMetadataProc.getSunGrid(aTile));
        tile.setViewingIncidenceAnglesGrids(L2aMetadataProc.getAnglesGrid(aTile));

        L2aMetadataProc.getMasks(aTile, file);
        tile.setMaskFilenames(L2aMetadataProc.getMasks(aTile, file));

        addTileToList(tile);

        addTileListToAllTileList(tile.getHorizontalCsCode(), getTileList());
    }
}