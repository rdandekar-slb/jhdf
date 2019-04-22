/*******************************************************************************
 * This file is part of jHDF. A pure Java library for accessing HDF5 files.
 *
 * http://jhdf.io
 *
 * Copyright 2019 James Mudd
 *
 * MIT License see 'LICENSE' file
 ******************************************************************************/
package io.jhdf.dataset;

import static java.lang.Math.toIntExact;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jhdf.HdfFileChannel;
import io.jhdf.ObjectHeader;
import io.jhdf.api.Group;
import io.jhdf.btree.BTreeV1;
import io.jhdf.btree.BTreeV1Data;
import io.jhdf.btree.BTreeV1Data.Chunk;
import io.jhdf.exceptions.HdfException;
import io.jhdf.filter.FilterManager;
import io.jhdf.filter.FilterPipeline;
import io.jhdf.object.message.DataLayoutMessage.ChunkedDataLayoutMessageV3;
import io.jhdf.object.message.FilterPipelineMessage;

/**
 * This represents chunked datasets using a b-tree for indexing raw data chunks.
 * It supports filters for use when reading the dataset for example to
 * decompress.
 *
 * @author James Mudd
 */
public class ChunkedDatasetV3 extends DatasetBase {
	private static final Logger logger = LoggerFactory.getLogger(ChunkedDatasetV3.class);

	private final LazyInitializer<Map<ChunkOffsetKey, Chunk>> chunkLookup;
	private final ConcurrentMap<ChunkOffsetKey, byte[]> decodedChunkLookup = new ConcurrentHashMap<>();

	private final ChunkedDataLayoutMessageV3 layoutMessage;

	private final FilterPipeline pipeline;

	public ChunkedDatasetV3(HdfFileChannel hdfFc, long address, String name, Group parent, ObjectHeader oh) {
		super(hdfFc, address, name, parent, oh);

		layoutMessage = oh.getMessageOfType(ChunkedDataLayoutMessageV3.class);

		// If the dataset has filters get the message
		if (oh.hasMessageOfType(FilterPipelineMessage.class)) {
			FilterPipelineMessage filterPipelineMessage = oh.getMessageOfType(FilterPipelineMessage.class);
			pipeline = FilterManager.getPipeline(filterPipelineMessage);
		} else {
			pipeline = null;
		}

		chunkLookup = new ChunkLookupLazyInitializer();
	}

	@Override
	public ByteBuffer getDataBuffer() {

		// Need to load the full buffer into memory so create the array
		byte[] dataArray = new byte[toIntExact(getDiskSize())];
		logger.trace("Created data buffer for '{}' of size {} bytes", getPath(), dataArray.length);

		int elementSize = getDataType().getSize();
		for (int i = 0; i < getSize(); i++) {
			int[] dimensionedIndex = linearIndexToDimensionIndex(i, getDimensions());
			long[] chunkOffset = getChunkOffset(dimensionedIndex);

			// Now figure out which element inside the chunk
			int[] insideChunk = new int[chunkOffset.length];
			for (int j = 0; j < chunkOffset.length; j++) {
				insideChunk[j] = (int) (dimensionedIndex[j] - chunkOffset[j]);
			}
			int insideChunkLinearOffset = dimensionIndexToLinearIndex(insideChunk, layoutMessage.getChunkDimensions());

			final byte[] chunkData = getDecodedChunk(new ChunkOffsetKey(chunkOffset));

			// Copy that data into the overall buffer
			System.arraycopy(chunkData, insideChunkLinearOffset * elementSize, dataArray, i * elementSize, elementSize);
		}

		return ByteBuffer.wrap(dataArray);
	}

	private byte[] getDecodedChunk(ChunkOffsetKey chunkKey) {
		return decodedChunkLookup.computeIfAbsent(chunkKey, this::decodeChunk);
	}

	private byte[] decodeChunk(ChunkOffsetKey key) {
		logger.debug("Decoding chunk '{}'", key);
		final Chunk chunk = getChunk(key);
		// Get the encoded (i.e. compressed buffer)
		final ByteBuffer encodedBuffer = getDataBuffer(chunk);

		// Get the encoded data from buffer
		final byte[] encodedBytes = new byte[encodedBuffer.remaining()];
		encodedBuffer.get(encodedBytes);

		if (pipeline == null) {
			// No filters
			logger.debug("No filters returning decoded chunk '{}'", chunk);
			return encodedBytes;
		}

		// Decode using the pipeline applying the filters
		final byte[] decodedBytes = pipeline.decode(encodedBytes);
		logger.debug("Decoded {}", chunk);

		return decodedBytes;
	}

	private Chunk getChunk(ChunkOffsetKey key) {
		try {
			return chunkLookup.get().get(key);
		} catch (Exception e) {
			throw new HdfException("Failed to create chunk lookup for '" + getPath() + "'");
		}
	}

	private ByteBuffer getDataBuffer(Chunk chunk) {
		try {
			return hdfFc.map(chunk.getAddress(), chunk.getSize());
		} catch (Exception e) {
			throw new HdfException(
					"Failed to read chunk for dataset '" + getPath() + "' at address " + chunk.getAddress());
		}
	}

	private int[] linearIndexToDimensionIndex(int index, int[] dimensions) {
		int[] dimIndex = new int[dimensions.length];

		for (int i = dimIndex.length - 1; i >= 0; i--) {
			dimIndex[i] = index % dimensions[i];
			index = index / dimensions[i];
		}
		return dimIndex;
	}

	private int dimensionIndexToLinearIndex(int[] index, int[] dimensions) {
		int linear = 0;
		for (int i = 0; i < dimensions.length; i++) {
			int temp = index[i];
			for (int j = i + 1; j < dimensions.length; j++) {
				temp *= dimensions[j];
			}
			linear += temp;
		}
		return linear;
	}

	private long[] getChunkOffset(int[] dimensionedIndex) {
		long[] chunkOffset = new long[dimensionedIndex.length];
		for (int i = 0; i < chunkOffset.length; i++) {
			long temp = toIntExact(layoutMessage.getChunkDimensions()[i]);
			chunkOffset[i] = (dimensionedIndex[i] / temp) * temp;
		}
		return chunkOffset;
	}

	private final class ChunkLookupLazyInitializer extends LazyInitializer<Map<ChunkOffsetKey, Chunk>> {
		@Override
		protected Map<ChunkOffsetKey, Chunk> initialize() throws ConcurrentException {
			logger.debug("Lazy initalizing chunk lookup for '{}'", getPath());
			BTreeV1Data bTree = BTreeV1.createDataBTree(hdfFc, layoutMessage.getBTreeAddress(),
					getDimensions().length);

			List<Chunk> chunks = bTree.getChunks();
			Map<ChunkOffsetKey, Chunk> chunkLookupMap = new HashMap<>(chunks.size());
			for (Chunk chunk : chunks) {
				chunkLookupMap.put(new ChunkOffsetKey(chunk.getChunkOffset()), chunk);
			}
			logger.debug("Created chunk lookup for '{}'", getPath());
			return chunkLookupMap;
		}
	}

	/**
	 * Custom key object for indexing chunks. It is optimised for fast hashcode and
	 * equals when looking up chunks.
	 */
	private class ChunkOffsetKey {
		final int hashcode;
		final long[] chunkOffset;

		private ChunkOffsetKey(long[] chunkOffset) {
			this.chunkOffset = chunkOffset;
			hashcode = Arrays.hashCode(chunkOffset);
		}

		@Override
		public int hashCode() {
			return hashcode;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (ChunkOffsetKey.class != obj.getClass())
				return false;
			ChunkOffsetKey other = (ChunkOffsetKey) obj;
			return Arrays.equals(chunkOffset, other.chunkOffset);
		}

		@Override
		public String toString() {
			return "ChunkOffsetKey [chunkOffset=" + Arrays.toString(chunkOffset) + ", hashcode=" + hashcode + "]";
		}

	}

}
