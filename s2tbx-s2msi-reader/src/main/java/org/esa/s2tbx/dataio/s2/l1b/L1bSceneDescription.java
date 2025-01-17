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

package org.esa.s2tbx.dataio.s2.l1b;

// import com.jcabi.aspects.Loggable;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.esa.s2tbx.dataio.s2.S2Metadata;
import org.esa.s2tbx.dataio.s2.S2SceneDescription;
import org.esa.s2tbx.dataio.s2.S2SpatialResolution;
import org.geotools.geometry.Envelope2D;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Norman Fomferra
 */
public class L1bSceneDescription extends S2SceneDescription {

    private final TileInfo[] tileInfos;
    private final Envelope2D sceneEnvelope;
    private final Rectangle sceneRectangle;
    private final Map<String, TileInfo> tileInfoMap;


    private static class TileInfo {
        private final int index;
        private final String id;
        private final Rectangle rectangle;

        public TileInfo(int index, String id, Rectangle rectangle) {
            this.index = index;
            this.id = id;
            this.rectangle = rectangle;
        }

        public String toString() {
            return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
        }
    }

    /**
     * Returns the scene description, or null if no scene description can be created (no tile of
     * no tile layout)
     *
     * @param header            the {@link L1bMetadata} object containing the tile list
     * @param productResolution the product resolution for which we want the scene description
     * @return the scene description of {@code null}
     */
    public static L1bSceneDescription create(L1bMetadata header, S2SpatialResolution productResolution) {
        L1bSceneDescription sceneDescription = null;

        List<S2Metadata.Tile> tileList = header.getTileList();
        // initialise with default CRS
        CoordinateReferenceSystem crs = DefaultGeographicCRS.WGS84;
        Envelope2D[] tileEnvelopes = new Envelope2D[tileList.size()];
        TileInfo[] tileInfos = new TileInfo[tileList.size()];
        Envelope2D sceneEnvelope = null;


        if (!tileList.isEmpty()) {


            Map<String, Integer> detectorsTopPositionMap = computeTopPositions(tileList, productResolution);

            for (int i = 0; i < tileList.size(); i++) {
                L1bMetadata.Tile tile = tileList.get(i);

                L1bMetadata.TileGeometry selectedGeometry = tile.getTileGeometry(productResolution);


                // Envelope2D envelope = new Envelope2D(selectedGeometry.envelope);

                Envelope2D envelope;

                // data is referenced through 1 based indexes
                // since position si computed for 10m, we need multiply by a ratio if product resolution is not 10m

                int ratio = productResolution.resolution / S2SpatialResolution.R10M.resolution;
                String detectorId = tile.getDetectorId();
                int topPosition = detectorsTopPositionMap.get(detectorId);
                int yOffset = (selectedGeometry.getPosition() - topPosition) / ratio;
                double yWidth = yOffset * selectedGeometry.getyDim();


                int xOffset = 0;

                envelope = new Envelope2D(crs,
                        xOffset,
                        yWidth + selectedGeometry.getNumRows() * selectedGeometry.getyDim(),
                        selectedGeometry.getNumCols() * selectedGeometry.getxDim(),
                        -selectedGeometry.getNumRows() * selectedGeometry.getyDim());

                tileEnvelopes[i] = envelope;

                if (sceneEnvelope == null) {
                    sceneEnvelope = new Envelope2D(crs, envelope);
                } else {
                    sceneEnvelope.add(envelope);
                }
                tileInfos[i] = new TileInfo(i, tile.getId(), new Rectangle());
            }

            // get back to upperLeft info in scene
            double imageX = sceneEnvelope.getX();
            double imageY = sceneEnvelope.getY() + sceneEnvelope.getHeight();
            Rectangle sceneBounds = null;
            for (int i = 0; i < tileEnvelopes.length; i++) {
                L1bMetadata.Tile tile = tileList.get(i);
                L1bMetadata.TileGeometry selectedGeometry = tile.getTileGeometry(productResolution);
                Envelope2D tileEnvelope = tileEnvelopes[i];

                // upperLeft again
                double tileX = tileEnvelope.getX();
                double tileY = tileEnvelope.getY() + tileEnvelope.getHeight();

                Rectangle rectangle = new Rectangle((int) ((tileX - imageX) / selectedGeometry.getxDim()),
                        (int) ((imageY - tileY) / -selectedGeometry.getyDim()),
                        selectedGeometry.getNumCols(),
                        selectedGeometry.getNumRows());
                if (sceneBounds == null) {
                    sceneBounds = new Rectangle(rectangle);
                } else {
                    sceneBounds.add(rectangle);
                }
                tileInfos[i] = new TileInfo(i, tile.getId(), rectangle);
            }

            sceneDescription = new L1bSceneDescription(tileInfos, sceneEnvelope, sceneBounds);
        }

        return sceneDescription;
    }

    private static Map<String, Integer> computeTopPositions(List<S2Metadata.Tile> tileList, S2SpatialResolution productResolution) {
        Map<String, Integer> detectorsTopPositionMap = new HashMap<>(12);

        for (S2Metadata.Tile tile : tileList) {
            String detectorId = tile.getDetectorId();
            Integer tilePosition = tile.getTileGeometry(productResolution).getPosition();
            Integer storedTopPosition = detectorsTopPositionMap.get(detectorId);
            if (storedTopPosition == null || storedTopPosition > tilePosition) {
                detectorsTopPositionMap.put(detectorId, tilePosition);
            }
        }

        return detectorsTopPositionMap;
    }

    private L1bSceneDescription(TileInfo[] tileInfos,
                                Envelope2D sceneEnvelope,
                                Rectangle sceneRectangle) {
        super();

        this.tileInfos = tileInfos;
        this.sceneEnvelope = sceneEnvelope;
        this.sceneRectangle = sceneRectangle;
        this.tileInfoMap = new HashMap<>();
        for (TileInfo tileInfo : tileInfos) {
            tileInfoMap.put(tileInfo.id, tileInfo);
        }
    }

    public Rectangle getSceneRectangle() {
        return sceneRectangle;
    }

    public Envelope2D getSceneEnvelope() {
        return sceneEnvelope;
    }

    public List<String> getTileIds() {
        final String[] tileIds = new String[tileInfos.length];
        for (int i = 0; i < tileInfos.length; i++) {
            tileIds[i] = tileInfos[i].id;
        }

        return new ArrayList<>(Arrays.asList(tileIds));
    }

    public int getTileIndex(String tileId) {
        TileInfo tileInfo = tileInfoMap.get(tileId);
        return tileInfo != null ? tileInfo.index : -1;
    }

    public Rectangle getTileRectangle(int tileIndex) {
        return tileInfos[tileIndex].rectangle;
    }

    public BufferedImage createTilePicture(int width) {

        Color[] colors = new Color[]{
                Color.GREEN,
                Color.RED,
                Color.BLUE,
                Color.YELLOW};

        double scale = width / sceneRectangle.getWidth();
        int height = (int) Math.round(sceneRectangle.getHeight() * scale);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.scale(scale, scale);
        graphics.translate(-sceneRectangle.getX(), -sceneRectangle.getY());
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setPaint(Color.WHITE);
        graphics.fill(sceneRectangle);
        graphics.setStroke(new BasicStroke(100F));
        graphics.setFont(new Font("Arial", Font.PLAIN, 800));

        for (int i = 0; i < tileInfos.length; i++) {
            Rectangle rect = tileInfos[i].rectangle;
            graphics.setPaint(addAlpha(colors[i % colors.length].brighter(), 100));
            graphics.fill(rect);
        }
        for (int i = 0; i < tileInfos.length; i++) {
            Rectangle rect = tileInfos[i].rectangle;
            graphics.setPaint(addAlpha(colors[i % colors.length].darker(), 100));
            graphics.draw(rect);
            graphics.setPaint(colors[i % colors.length].darker().darker());
            graphics.drawString("Tile " + (i + 1) + ": " + tileInfos[i].id,
                    rect.x + 1200F,
                    rect.y + 2200F);
        }
        return image;
    }

    private static Color addAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
    }
}
