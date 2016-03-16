/*
 * Copyright (C) 2014-2015 CS-SI (foss-contact@thor.si.c-s.fr)
 * Copyright (C) 2014-2015 CS-Romania (office@c-s.ro)
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

package org.esa.s2tbx.dataio.readers;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.s2tbx.dataio.FileImageInputStreamSpi;
import org.esa.s2tbx.dataio.VirtualDirEx;
import org.esa.s2tbx.dataio.metadata.XmlMetadata;
import org.esa.s2tbx.dataio.metadata.XmlMetadataParser;
import org.esa.s2tbx.dataio.metadata.XmlMetadataParserFactory;
import org.esa.snap.core.dataio.AbstractProductReader;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.image.ImageManager;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.core.util.TreeNode;
import org.esa.snap.dataio.geotiff.GeoTiffProductReader;
import org.esa.snap.utils.CollectionHelper;

import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageInputStreamSpi;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for all GeoTIFF-based readers for S2TBX.
 * This class has been created from the need of gathering all common code of several similar readers into a single place.
 */
public abstract class GeoTiffBasedReader<M extends XmlMetadata> extends AbstractProductReader {

    private final Class<M> metadataClass;
    protected List<M> metadata;
    protected Product product;
    protected final Logger logger;
    protected ImageInputStreamSpi imageInputStreamSpi;
    protected VirtualDirEx productDirectory;
    protected final Map<Band, Band> bandMap;
    protected List<String> rasterFileNames;

    protected GeoTiffBasedReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
        logger = Logger.getLogger(GeoTiffBasedReader.class.getName());
        this.metadataClass = getTypeArgument();
        registerMetadataParser();
        registerSpi();
        bandMap = new HashMap<>();
        metadata = new ArrayList<>();
    }

    protected Class<M> getTypeArgument() {
        Class<M> arg;
        Type superclass = getClass().getGenericSuperclass();
        Type type;
        if (superclass instanceof  ParameterizedType) {
            type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
            arg = (Class<M>) type;
        } else if ((superclass = getClass().getSuperclass().getGenericSuperclass()) instanceof ParameterizedType) {
            type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
            arg = (Class<M>) type;
        } else {
            throw new ClassCastException("Cannot find parameterized type");
        }
        return arg;
    }

    /**
     * Gets the metadata file extension.
     *
     * @return the metadata file extension
     */
    protected abstract String getMetadataExtension();

    /**
     * Gets the profile of the metadata.
     *
     * @return the profile of the metadata.
     */
    protected abstract String getMetadataProfile();

    /**
     * Gets a generic product name, in case none is found in metadata
     *
     * @return  the generic product name
     */
    protected abstract String getProductGenericName();

    protected abstract String getMetadataFileSuffix();

    /**
     * Gets the names of the bands.
     * It is the responsibility of the extender to provide the band names
     * either from metadata, or from predefined constants.
     *
     * @return  An array with the band names.
     */
    protected abstract String[] getBandNames();

    @Override
    public void close() throws IOException {
        if (productDirectory != null) {
            productDirectory.close();
        }
        if (imageInputStreamSpi != null) {
            IIORegistry.getDefaultInstance().deregisterServiceProvider(imageInputStreamSpi);
        }
        super.close();
    }

    /**
     * Registers a customized XML parser for the metadata type of this reader, in the XML metadata parser factory.
     */
    protected void registerMetadataParser() {
        XmlMetadataParserFactory.registerParser(this.metadataClass, new XmlMetadataParser<>(this.metadataClass));
    }

    /**
     * Registers a file image input strwM SPI for image input stream, if none is yet registered.
     */
    protected void registerSpi() {
        final IIORegistry defaultInstance = IIORegistry.getDefaultInstance();
        Iterator<ImageInputStreamSpi> serviceProviders = defaultInstance.getServiceProviders(ImageInputStreamSpi.class, true);
        ImageInputStreamSpi toUnorder = null;
        if (defaultInstance.getServiceProviderByClass(FileImageInputStreamSpi.class) == null) {
            // register only if not already registered
            while (serviceProviders.hasNext()) {
                ImageInputStreamSpi current = serviceProviders.next();
                if (current.getInputClass() == File.class) {
                    toUnorder = current;
                    break;
                }
            }
            imageInputStreamSpi = new FileImageInputStreamSpi();
            defaultInstance.registerServiceProvider(imageInputStreamSpi);
            if (toUnorder != null) {
                // Make the custom Spi to be the first one to be used.
                defaultInstance.setOrdering(ImageInputStreamSpi.class, imageInputStreamSpi, toUnorder);
            }
        }
    }

    /**
     * Returns a File object from the input of the reader.
     * @param input the input object
     * @return  Either a new instance of File, if the input represents the file name, or the casted input File.
     */
    protected File getFileInput(Object input) {
        if (input instanceof String) {
            return new File((String) input);
        } else if (input instanceof File) {
            return (File) input;
        }
        return null;
    }

    /**
     * Returns a wrapping VirtualDirEx object over the input product.
     *
     * @param input The reader input as received from the caller.
     * @return  An instance of VirtualDirEx
     * @throws IOException
     */
    protected VirtualDirEx getInput(Object input) throws IOException {
        File inputFile = getFileInput(input);
        if (inputFile.isFile() && !VirtualDirEx.isPackedFile(inputFile)) {
            final File absoluteFile = inputFile.getAbsoluteFile();
            inputFile = absoluteFile.getParentFile();
            if (inputFile == null) {
                throw new IOException(String.format("Unable to retrieve parent to file %s.", absoluteFile.getAbsolutePath()));
            }
        }
        return VirtualDirEx.create(inputFile);
    }

    /**
     * Returns the preferred tile size, either from product (if defined) or from the underlying ImageManager.
     *
     * @return  The preferred tile dimensions.
     */
    protected Dimension getPreferredTileSize() {
        Dimension tileSize = null;
        if (product != null) {
            tileSize = product.getPreferredTileSize();
            if (tileSize == null) {
                Dimension suggestedTileSize = ImageManager.getPreferredTileSize(product);
                tileSize = new Dimension((int)suggestedTileSize.getWidth(), (int)suggestedTileSize.getHeight());
            }
        }
        return tileSize;
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        productDirectory = getInput(super.getInput());
        if (getReaderPlugIn().getDecodeQualification(super.getInput()) == DecodeQualification.UNABLE) {
            throw new IOException("The selected product cannot be read with the current reader.");
        }
        String[] metadataFiles = productDirectory.findAll(getMetadataExtension());
        if (metadataFiles != null) {
            logger.info("Reading product metadata");
            for (String file : metadataFiles) {
                try {
                    File metadataFile = productDirectory.getFile(file);
                    M mData = XmlMetadata.create(metadataClass, metadataFile);
                    mData.setFileName(metadataFile.getName());
                    metadata.add(mData);
                } catch (Exception mex) {
                    logger.warning(String.format("Error while reading metadata file %s", file));
                }
            }
        } else {
            logger.info("No metadata file found");
        }
        if (metadata != null && metadata.size() > 0) {
            List<M> rasterMetadataList = CollectionHelper.where(metadata, m -> m.getFileName().endsWith(getMetadataFileSuffix()));

            M firstMetadata;
            String metadataProfile;
            if (rasterMetadataList == null || rasterMetadataList.size() == 0 || (firstMetadata = rasterMetadataList.get(0)) == null || ((metadataProfile = firstMetadata.getMetadataProfile()) == null || !metadataProfile.startsWith(getMetadataProfile()))) {
                IOException ex = new IOException("The selected product is not readable by this reader. Please use the appropriate filter");
                logger.log(Level.SEVERE, ex.getMessage(), ex);
                throw ex;
            }
            if (firstMetadata.getRasterWidth() > 0 && firstMetadata.getRasterHeight() > 0) {
                createProduct(firstMetadata.getRasterWidth(), firstMetadata.getRasterHeight(), firstMetadata);
            }
            for (int i = 0; i < rasterMetadataList.size(); i++) {
                M currentMetadata = rasterMetadataList.get(i);
                addBands(product, currentMetadata, i);
            }
            addMetadataMasks(product, firstMetadata);
            readAdditionalMasks(productDirectory);

            product.setModified(false);
        } else {
            product.setModified(false);
        }
        return  product;
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY, Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) throws IOException {
        Band sourceBand = bandMap.get(destBand);
        GeoTiffReaderEx reader = (GeoTiffReaderEx)sourceBand.getProductReader();
        if (reader == null) {
            logger.severe("No reader found for band data");
        } else {
            reader.readBandRasterDataImpl(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight, sourceStepX, sourceStepY, sourceBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
        }
    }

    /**
     * Creates and initializes the product to be manipulated by this reader.
     *
     * @param width the width (in pixels) of the product
     * @param height    the height (in pixels) of the product
     * @param metadataFile  The (primary) metadata file
     * @return  An instance of the product
     */
    protected Product createProduct(int width, int height, M metadataFile) {
        product = new Product((metadataFile != null && metadataFile.getProductName() != null) ? metadataFile.getProductName() : getProductGenericName(),
                              getReaderPlugIn().getFormatNames()[0],
                              width, height);
        File fileLocation = null;
        try {
            // in case of zip products, getTempDir returns the temporary location of the uncompressed product
            fileLocation = productDirectory.getTempDir();
        } catch (IOException e) {
            logger.warning(e.getMessage());
        }
        if (fileLocation == null) {
            fileLocation = new File(productDirectory.getBasePath());
        }
        product.setFileLocation(fileLocation);
        if (metadataFile != null) {
            product.getMetadataRoot().addElement(metadataFile.getRootElement());
            ProductData.UTC centerTime = metadataFile.getCenterTime();
            if (centerTime != null) {
                product.setStartTime(centerTime);
                product.setEndTime(centerTime);
            } else {
                product.setStartTime(metadataFile.getProductStartTime());
                product.setEndTime(metadataFile.getProductEndTime());
            }
            product.setProductType(metadataFile.getMetadataProfile());
            product.setDescription(metadataFile.getProductDescription());
        }
        return product;
    }

    /**
     * Reads the product bands from the product rasters, using the given component metadata (a product may have several components, and hence
     * several metadata files).
     *
     * @param product   The instance of the product to which bands will be added
     * @param componentMetadata The metadata of the original product component
     * @param componentIndex    The index of the current product component (0 if only one component)
     */
    protected void addBands(Product product, M componentMetadata, int componentIndex) {
        try {
            rasterFileNames = getRasterFileNames();
            if (componentIndex >= rasterFileNames.size()) {
                throw new ArrayIndexOutOfBoundsException(String.format("Invalid component index: %d", componentIndex));
            }
            File rasterFile = productDirectory.getFile(rasterFileNames.get(componentIndex));

            GeoTiffProductReader reader = new GeoTiffReaderEx(getReaderPlugIn());
            Product tiffProduct = reader.readProductNodes(rasterFile, null);
            if (tiffProduct != null) {
                if (product == null) {
                    product = createProduct(tiffProduct.getSceneRasterWidth(), tiffProduct.getSceneRasterHeight(), componentMetadata);
                }
                MetadataElement tiffMetadata = tiffProduct.getMetadataRoot();
                if (tiffMetadata != null) {
                    XmlMetadata.CopyChildElements(tiffMetadata, product.getMetadataRoot());
                }
                tiffProduct.transferGeoCodingTo(product, null);
                Dimension preferredTileSize = tiffProduct.getPreferredTileSize();
                if (preferredTileSize == null)
                    preferredTileSize = getPreferredTileSize();
                product.setPreferredTileSize(preferredTileSize);
                int numBands = tiffProduct.getNumBands();
                String bandPrefix = "";
                if (rasterFileNames.size() > 1) {
                    bandPrefix = "scene_" + String.valueOf(componentIndex) + "_";
                    String groupPattern = computeGroupPattern();
                    if (!StringUtils.isNullOrEmpty(groupPattern)) {
                        product.setAutoGrouping(groupPattern);
                    }
                }
                for (int idx = 0; idx < numBands; idx++) {
                    Band srcBand = tiffProduct.getBandAt(idx);
                    String bandName = bandPrefix + getBandNames()[idx];
                    Band targetBand = product.addBand(bandName, srcBand.getDataType());
                    targetBand.setNoDataValue(srcBand.getNoDataValue());
                    targetBand.setNoDataValueUsed(srcBand.isNoDataValueUsed());
                    targetBand.setSpectralWavelength(srcBand.getSpectralWavelength());
                    targetBand.setSpectralBandwidth(srcBand.getSpectralBandwidth());
                    targetBand.setScalingFactor(srcBand.getScalingFactor());
                    targetBand.setScalingOffset(srcBand.getScalingOffset());
                    targetBand.setSolarFlux(srcBand.getSolarFlux());
                    targetBand.setUnit(srcBand.getUnit());
                    targetBand.setSampleCoding(srcBand.getSampleCoding());
                    targetBand.setImageInfo(srcBand.getImageInfo());
                    targetBand.setSpectralBandIndex(srcBand.getSpectralBandIndex());
                    targetBand.setDescription(srcBand.getDescription());
                    bandMap.put(targetBand, srcBand);
                }
            }
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
    }

    /**
     * Reads from the given metadata object and adds product masks.
     * The default (i.e. base) implementation does nothing, therefore this method should be overridden by subclasses.
     * @param product   The product to which masks should be added.
     * @param metadata  The metadata object from which masks are read.
     */
    protected void addMetadataMasks(Product product, M metadata) {
    }

    /**
     * Reads masks not found in metadata from additional mask files (if any).
     * The default (i.e. base) implementation does nothing, therefore this method should be overridden by subclasses.
     * @param directory The virtual directory of the product.
     */
    protected void readAdditionalMasks(VirtualDirEx directory) {

    }

    protected void addProductComponentIfNotPresent(String componentId, File componentFile, TreeNode<File> currentComponents) {
        TreeNode<File> resultComponent = null;
        for (TreeNode node : currentComponents.getChildren()) {
            if (node.getId().toLowerCase().equals(componentId.toLowerCase())) {
                //noinspection unchecked
                resultComponent = node;
                break;
            }
        }
        if (resultComponent == null) {
            resultComponent = new TreeNode<>(componentId, componentFile);
            currentComponents.addChild(resultComponent);
        }
    }

    /**
     * Returns a list of raster file names for the product.
     * @return  a list of raster file names
     */
    protected List<String> getRasterFileNames() {
        if (rasterFileNames == null) {
            rasterFileNames = new ArrayList<>();
            if (metadata != null) {
                for (M metadataComponent : metadata) {
                    String[] partialList = metadataComponent.getRasterFileNames();
                    if (partialList != null) {
                        rasterFileNames.addAll(Arrays.asList(partialList));
                    }
                }
            }
            if (rasterFileNames.size() == 0) {
                try {
                    String[] allTiffFiles = productDirectory.findAll(".tif");
                    if (allTiffFiles != null) {
                        rasterFileNames.addAll(Arrays.asList(allTiffFiles));
                    }
                } catch (IOException e) {
                    logger.warning(e.getMessage());
                }
            }
        }
        return rasterFileNames;
    }

    /**
     * Computes the grouping of bands if the product has multiple raster files.
     * @return  the grouping expression or an empty string if there is at most one raster file.
     */
    protected String computeGroupPattern() {
        String groupPattern = "";
        rasterFileNames = getRasterFileNames();
        if (rasterFileNames.size() > 1) {
            for (int idx = 0; idx < rasterFileNames.size(); idx++) {
                groupPattern += "scene_" + String.valueOf(idx) + ":";
            }
        }
        return groupPattern.substring(0, groupPattern.length() - 1);
    }

    @Override
    public TreeNode<File> getProductComponents() {
        TreeNode<File> result = super.getProductComponents();
        if (productDirectory.isCompressed()) {
            return result;
        } else {
            TreeNode<File>[] nodesClone = result.getChildren().clone();
            for(TreeNode<File> node : nodesClone){
                result.removeChild(node);
            }
            for(XmlMetadata metaFile: metadata){
                TreeNode<File> productFile = new TreeNode<>(metaFile.getFileName());
                result.addChild(productFile);
                /*try {
                    TreeNode<File> productFile = new TreeNode<File>(metaFile.getFileName());
                    productFile.setContent(input.getFile(inputFile));
                    result.addChild(productFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }*/
            }
            for(String inputFile: getRasterFileNames()){
                TreeNode<File> productFile = new TreeNode<>(inputFile);
                result.addChild(productFile);
                /*try {
                    TreeNode<File> productFile = new TreeNode<File>(inputFile);
                    productFile.setContent(input.getFile(inputFile));
                    result.addChild(productFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }*/
            }
            return result;
        }
    }

    /**
     * We need this class in order to raise the visibility of readBandRasterDataImpl method,
     * in order to be able to pass to the underlying GeoTiffProductReader the stepping parameters.
     */
    protected class GeoTiffReaderEx extends GeoTiffProductReader {

        public GeoTiffReaderEx(ProductReaderPlugIn readerPlugIn) {
            super(readerPlugIn);
        }

        @Override
        protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY, Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) throws IOException {
            super.readBandRasterDataImpl(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight, sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
        }
    }
}

