package nom.tam.image.compression.tile;

/*
 * #%L
 * nom.tam FITS library
 * %%
 * Copyright (C) 1996 - 2015 nom-tam-fits
 * %%
 * This is free and unencumbered software released into the public domain.
 * 
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 * 
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * #L%
 */

import java.lang.reflect.Array;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import nom.tam.fits.compression.algorithm.api.ICompressOption;
import nom.tam.image.compression.tile.buffer.TileBuffer;
import nom.tam.util.type.PrimitiveType;
import nom.tam.util.type.PrimitiveTypeHandler;

/**
 * abstract information holder about the a tile that represents a rectangular
 * part of the image. Will be sub classed for compression and decompression
 * variants.
 */
abstract class TileOperation implements Runnable {

    protected final TiledImageOperation tiledImageOperation;

    protected ByteBuffer compressedData;

    protected int compressedOffset;

    protected TileCompressionType compressionType;

    protected Future<?> future;

    protected TileBuffer tileBuffer;

    protected final int tileIndex;

    protected ICompressOption tileOptions;

    protected TileOperation(TiledImageOperation array, int tileIndex) {
        this.tiledImageOperation = array;
        this.tileIndex = tileIndex;
    }

    private ByteBuffer convertToBuffer(Object data) {
        return PrimitiveTypeHandler.valueOf(data.getClass().getComponentType()).convertToByteBuffer(data);
    }

    protected void execute(ExecutorService threadPool) {
        this.future = threadPool.submit(this);
    }

    protected byte[] getCompressedData() {
        byte[] data = new byte[this.compressedData.limit()];
        this.compressedData.rewind();
        PrimitiveType.BYTE.getArray(this.compressedData, data);
        return data;
    }

    protected TileCompressionType getCompressionType() {
        return this.compressionType;
    }

    /**
     * @return the number of pixels in this tile.
     */
    protected int getPixelSize() {
        return this.tileBuffer.getPixelSize();
    }

    protected int getTileIndex() {
        return this.tileIndex;
    }

    protected ICompressOption getTileOptions() {
        return this.tileOptions;
    }

    protected TileOperation initTileOptions() {
        ICompressOption compressOptions = this.tiledImageOperation.compressOptions();
        this.tileOptions = compressOptions.copy() //
                .setTileWidth(this.tileBuffer.getWidth()) //
                .setTileHeight(this.tileBuffer.getHeight());
        return this;
    }

    protected TileOperation setCompressed(Object data, TileCompressionType type) {
        if (data != null && Array.getLength(data) > 0) {
            this.compressionType = type;
            this.compressedData = convertToBuffer(data);
            this.compressedOffset = 0;
        }
        return this;
    }

    protected TileOperation setCompressedOffset(int value) {
        this.compressedOffset = value;
        return this;
    }

    protected TileOperation setDimensions(int dataOffset, int width, int height) {
        this.tileBuffer = TileBuffer.createTileBuffer(this.tiledImageOperation.getBaseType(), //
                dataOffset, //
                this.tiledImageOperation.getImageWidth(), //
                width, height);
        return this;
    }

    /**
     * set the buffer that describes the whole image and let the tile create a
     * slice of it from the position where the tile starts in the whole image.
     * Attention this method is not thread-safe because it changes the position
     * of the buffer parameter.
     *
     * @param buffer
     *            the buffer that describes the whole image.
     */
    protected void setWholeImageBuffer(Buffer buffer) {
        this.tileBuffer.setDecompressedData(buffer);
    }

    /**
     * set the buffer that describes the whole compressed image and let the tile
     * create a slice of it from the position where the tile starts in the whole
     * image. Attention this method is not thread-safe because it changes the
     * position of the buffer parameter. This buffer is just as big as the image
     * buffer but will be reduced to the needed size as a last step of the
     * Compression.
     *
     * @param compressed
     *            the buffer that describes the whole image.
     */
    protected void setWholeImageCompressedBuffer(ByteBuffer compressed) {
        compressed.position(this.compressedOffset * this.tiledImageOperation.getBaseType().size());
        this.compressedData = compressed.slice();
        this.compressedOffset = 0;
        // we do not limit this buffer but is expected not to write more than
        // the uncompressed size.
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + this.tileIndex + "," + this.compressionType + "," + this.compressedOffset + ")";
    }

    protected void waitForResult() {
        try {
            this.future.get();
        } catch (Exception e) {
            throw new IllegalStateException("could not (de)compress tile", e);
        }
    }
}
