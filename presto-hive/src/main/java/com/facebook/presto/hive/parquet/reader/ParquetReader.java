/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive.parquet.reader;

import com.facebook.presto.hive.parquet.ParquetCodecFactory;
import com.facebook.presto.hive.parquet.ParquetCorruptionException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.type.Type;
import com.google.common.primitives.Ints;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import parquet.column.ColumnDescriptor;
import parquet.column.page.PageReadStore;
import parquet.hadoop.metadata.BlockMetaData;
import parquet.hadoop.metadata.ColumnChunkMetaData;
import parquet.hadoop.metadata.ColumnPath;
import parquet.schema.MessageType;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.facebook.presto.hive.parquet.ParquetValidationUtils.validateParquet;

public class ParquetReader
        implements Closeable
{
    public static final int MAX_VECTOR_LENGTH = 1024;

    private final Path file;
    private final MessageType fileSchema;
    private final Map<String, String> extraMetadata;
    private final MessageType requestedSchema;
    private final List<BlockMetaData> blocks;
    private final Configuration configuration;
    private final FSDataInputStream inputStream;
    private final ParquetCodecFactory codecFactory;

    private int currentBlock;
    private BlockMetaData currentBlockMetadata;
    private PageReadStore readerStore;
    private long fileRowCount;
    private long currentPosition;
    private long currentGroupRowCount;
    private long nextRowInGroup;
    private Map<ColumnDescriptor, ParquetColumnReader> columnReadersMap = new HashMap<>();

    public ParquetReader(MessageType fileSchema,
            Map<String, String> extraMetadata,
            MessageType requestedSchema,
            Path file,
            List<BlockMetaData> blocks,
            Configuration configuration)
            throws IOException
    {
        this.fileSchema = fileSchema;
        this.extraMetadata = extraMetadata;
        this.requestedSchema = requestedSchema;
        this.file = file;
        this.blocks = blocks;
        this.configuration = configuration;
        inputStream = file.getFileSystem(configuration).open(file);
        codecFactory = new ParquetCodecFactory(configuration);
        for (BlockMetaData block : blocks) {
            fileRowCount += block.getRowCount();
        }
        initializeColumnReaders();
    }

    @Override
    public void close()
            throws IOException
    {
        inputStream.close();
        codecFactory.release();
    }

    public float getProgress()
            throws IOException, InterruptedException
    {
        if (fileRowCount == 0) {
            return 0.0f;
        }
        return (float) currentPosition / fileRowCount;
    }

    public long getPosition()
    {
        return currentPosition;
    }

    public long getFileRowCount()
    {
        return fileRowCount;
    }

    public int nextBatch()
            throws IOException, InterruptedException
    {
        if (nextRowInGroup >= currentGroupRowCount) {
            if (!advanceToNextRowGroup()) {
                return -1;
            }
        }

        int batchSize = Ints.checkedCast(Math.min(MAX_VECTOR_LENGTH, currentGroupRowCount - nextRowInGroup));

        nextRowInGroup += batchSize;
        currentPosition += batchSize;
        for (ColumnDescriptor column : requestedSchema.getColumns()) {
            ParquetColumnReader columnReader = columnReadersMap.get(column);
            columnReader.prepareNextRead(batchSize);
        }
        return batchSize;
    }

    private boolean advanceToNextRowGroup()
            throws InterruptedException
    {
        if (currentBlock == blocks.size()) {
            return false;
        }
        currentBlockMetadata = blocks.get(currentBlock);
        currentBlock = currentBlock + 1;
        long rowCount = currentBlockMetadata.getRowCount();

        nextRowInGroup = 0L;
        currentGroupRowCount = rowCount;
        columnReadersMap.clear();
        initializeColumnReaders();
        return true;
    }

    public Block readBlock(ColumnDescriptor columnDescriptor, Type type)
            throws IOException
    {
        ParquetColumnReader columnReader = columnReadersMap.get(columnDescriptor);
        if (columnReader.getPageReader() == null) {
            validateParquet(currentBlockMetadata.getRowCount() > 0, "Row group having 0 rows");
            ColumnChunkMetaData metadata = getColumnChunkMetaData(columnDescriptor);
            long startingPosition = metadata.getStartingPos();
            inputStream.seek(startingPosition);
            int totalSize = Ints.checkedCast(metadata.getTotalSize());
            byte[] buffer = new byte[totalSize];
            inputStream.readFully(buffer);
            ParquetColumnChunkDescriptor descriptor = new ParquetColumnChunkDescriptor(columnDescriptor, metadata, startingPosition, totalSize);
            ParquetColumnChunk columnChunk = new ParquetColumnChunk(descriptor, buffer, 0, codecFactory);
            columnReader.setPageReader(columnChunk.readAllPages());
        }
        return columnReader.readBlock(type);
    }

    private ColumnChunkMetaData getColumnChunkMetaData(ColumnDescriptor columnDescriptor)
            throws IOException
    {
        for (ColumnChunkMetaData metadata : currentBlockMetadata.getColumns()) {
            if (metadata.getPath().equals(ColumnPath.get(columnDescriptor.getPath()))) {
                return metadata;
            }
        }
        throw new ParquetCorruptionException("Malformed Parquet file. Could not find column metadata %s", columnDescriptor);
    }

    private void initializeColumnReaders()
    {
        for (ColumnDescriptor column : requestedSchema.getColumns()) {
            columnReadersMap.put(column, ParquetColumnReader.createReader(column));
        }
    }
}
