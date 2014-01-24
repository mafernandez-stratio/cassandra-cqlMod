/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package io.stratio.sdh.secondaries;

import java.nio.ByteBuffer;
import java.util.Set;
import org.apache.cassandra.db.Cell;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.index.SecondaryIndex;
import org.apache.cassandra.db.index.SecondaryIndexSearcher;
import org.apache.cassandra.exceptions.ConfigurationException;

/**
 *
 * @author Strat.io
 */
public class PerColumnLuceneIdx extends SecondaryIndex
{

    @Override
    public void init() {
        logger.info("Fake PerColumnLuceneIdx init method");
    }

    @Override
    public void reload() {
        logger.info("Fake PerColumnLuceneIdx reload method");
    }

    @Override
    public void validateOptions() throws ConfigurationException {
        logger.info("Fake PerColumnLuceneIdx validateOptions method");
    }

    @Override
    public String getIndexName() {
        logger.info("Fake PerColumnLuceneIdx getIndexName method");
        return "Fake PerColumnLuceneIdx String";
    }

    @Override
    public String getNameForSystemKeyspace(ByteBuffer columnName) {
        logger.info("Fake PerColumnLuceneIdx getNameForSystemKeyspace method");
        return "Fake PerColumnLuceneIdx String";
    }

    @Override
    protected SecondaryIndexSearcher createSecondaryIndexSearcher(Set<ByteBuffer> columns) {
        logger.info("Fake PerColumnLuceneIdx createSecondaryIndexSearcher method");
        return null;
    }

    @Override
    public void forceBlockingFlush() {
        logger.info("Fake PerColumnLuceneIdx forceBlockingFlush method");
    }

    @Override
    public long getLiveSize() {
        logger.info("Fake PerColumnLuceneIdx getLiveSize method");
        return (long) Math.random()*Long.MAX_VALUE;
    }

    @Override
    public ColumnFamilyStore getIndexCfs() {
        logger.info("Fake PerColumnLuceneIdx getIndexCfs method");
        return null;
    }

    @Override
    public void removeIndex(ByteBuffer columnName) {
        logger.info("Fake PerColumnLuceneIdx removeIndex method");
    }

    @Override
    public void invalidate() {
        logger.info("Fake PerColumnLuceneIdx invalidate method");
    }

    @Override
    public void truncateBlocking(long truncatedAt) {
        logger.info("Fake PerColumnLuceneIdx truncateBlocking method");
    }

    @Override
    public boolean indexes(CellName name) {
        logger.info("Fake PerColumnLuceneIdx indexes method");
        return false;
    }

    @Override
    public boolean validate(Cell cell) {
        logger.info("Fake PerColumnLuceneIdx validate method");
        return false;
    }

    @Override
    public long estimateResultRows() {
        logger.info("Fake PerColumnLuceneIdx estimateResultRows method");
        return (long) Math.random()*Long.MAX_VALUE;
    }
    
}
