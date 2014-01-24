/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.nio.ByteBuffer;
import java.util.*;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import org.github.jamm.MemoryMeter;

import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.cql3.*;
import org.apache.cassandra.db.composites.*;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.dht.*;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.service.pager.*;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.thrift.ThriftValidation;
import org.apache.cassandra.serializers.MarshalException;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates a completely parsed SELECT query, including the target
 * column family, expression, result count, and ordering clause.
 *
 */
public class SelectStatement implements CQLStatement, MeasurableForPreparedCache
{
    private static final int DEFAULT_COUNT_PAGE_SIZE = 10000;

    private final int boundTerms;
    public final CFMetaData cfm;
    public final Parameters parameters;
    private final Selection selection;
    private final Term limit;

    private final Restriction[] keyRestrictions;
    private final Restriction[] columnRestrictions;
    private final Map<ColumnIdentifier, Restriction> metadataRestrictions = new HashMap<ColumnIdentifier, Restriction>();

    // All restricted columns not covered by the key or index filter
    private final Set<ColumnDefinition> restrictedColumns = new HashSet<ColumnDefinition>();
    private Restriction.Slice sliceRestriction;

    private boolean isReversed;
    private boolean onToken;
    private boolean isKeyRange;
    private boolean keyIsInRelation;
    private boolean usesSecondaryIndexing;
    private boolean lastClusteringIsIn;

    private Map<ColumnIdentifier, Integer> orderingIndexes;

    // Used by forSelection below
    private static final Parameters defaultParameters = new Parameters(Collections.<ColumnIdentifier, Boolean>emptyMap(), false, false, null, false);

    private static final Logger logger = LoggerFactory.getLogger(SelectStatement.class);
    
    public SelectStatement(CFMetaData cfm, int boundTerms, Parameters parameters, Selection selection, Term limit)
    {
        this.cfm = cfm;
        this.boundTerms = boundTerms;
        this.selection = selection;
        this.keyRestrictions = new Restriction[cfm.partitionKeyColumns().size()];
        this.columnRestrictions = new Restriction[cfm.clusteringColumns().size()];
        this.parameters = parameters;
        this.limit = limit;
        logger.info(">>> STRATIO >>> SelectStatement constructor");
    }

    // Creates a simple select based on the given selection.
    // Note that the results select statement should not be used for actual queries, but only for processing already
    // queried data through processColumnFamily.
    static SelectStatement forSelection(CFMetaData cfm, Selection selection)
    {
        return new SelectStatement(cfm, 0, defaultParameters, selection, null);
    }

    public ResultSet.Metadata getResultMetadata()
    {
        return parameters.isCount
             ? ResultSet.makeCountMetadata(keyspace(), columnFamily(), parameters.countAlias)
             : selection.getResultMetadata();
    }

    public long measureForPreparedCache(MemoryMeter meter)
    {
        return meter.measureDeep(this) - meter.measureDeep(cfm);
    }

    public int getBoundTerms()
    {
        return boundTerms;
    }

    public void checkAccess(ClientState state) throws InvalidRequestException, UnauthorizedException
    {
        state.hasColumnFamilyAccess(keyspace(), columnFamily(), Permission.SELECT);
    }

    public void validate(ClientState state) throws InvalidRequestException
    {
        // Nothing to do, all validation has been done by RawStatement.prepare()
    }

    public ResultMessage.Rows execute(QueryState state, QueryOptions options) throws RequestExecutionException, RequestValidationException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.execute(...2)");
        
        ConsistencyLevel cl = options.getConsistency();
        List<ByteBuffer> variables = options.getValues();
        if (cl == null)
            throw new InvalidRequestException("Invalid empty consistency level");

        cl.validateForRead(keyspace());

        int limit = getLimit(variables);
        long now = System.currentTimeMillis();
        Pageable command;
        if (isKeyRange || usesSecondaryIndexing)
        {
            command = getRangeCommand(variables, limit, now);
        }
        else
        {
            List<ReadCommand> commands = getSliceCommands(variables, limit, now);
            command = commands == null ? null : new Pageable.ReadCommands(commands);
        }

        int pageSize = options.getPageSize();
        // A count query will never be paged for the user, but we always page it internally to avoid OOM.
        // If we user provided a pageSize we'll use that to page internally (because why not), otherwise we use our default
        if (parameters.isCount && pageSize <= 0)
            pageSize = DEFAULT_COUNT_PAGE_SIZE;

        if (pageSize <= 0 || command == null || !QueryPagers.mayNeedPaging(command, pageSize))
        {
            return execute(command, cl, variables, limit, now);
        }
        else
        {
            QueryPager pager = QueryPagers.pager(command, cl, options.getPagingState());
            if (parameters.isCount)
                return pageCountQuery(pager, variables, pageSize, now);

            List<Row> page = pager.fetchPage(pageSize);
            ResultMessage.Rows msg = processResults(page, variables, limit, now);
            if (!pager.isExhausted())
                msg.result.metadata.setHasMorePages(pager.state());
            return msg;
        }
    }

    private ResultMessage.Rows execute(Pageable command, ConsistencyLevel cl, List<ByteBuffer> variables, int limit, long now) throws RequestValidationException, RequestExecutionException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.execute(...5)");
        
        List<Row> rows;
        if (command == null)
        {
            rows = Collections.<Row>emptyList();
        }
        else
        {
            rows = command instanceof Pageable.ReadCommands
                 ? StorageProxy.read(((Pageable.ReadCommands)command).commands, cl)
                 : StorageProxy.getRangeSlice((RangeSliceCommand)command, cl);
        }

        return processResults(rows, variables, limit, now);
    }

    private ResultMessage.Rows pageCountQuery(QueryPager pager, List<ByteBuffer> variables, int pageSize, long now) throws RequestValidationException, RequestExecutionException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.pageCountQuery(...)");
        
        int count = 0;
        while (!pager.isExhausted())
        {
            int maxLimit = pager.maxRemaining();
            ResultSet rset = process(pager.fetchPage(pageSize), variables, maxLimit, now);
            count += rset.rows.size();
        }

        ResultSet result = ResultSet.makeCountResult(keyspace(), columnFamily(), count, parameters.countAlias);
        return new ResultMessage.Rows(result);
    }

    public ResultMessage.Rows processResults(List<Row> rows, List<ByteBuffer> variables, int limit, long now) throws RequestValidationException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.processResults(...)");
        
        // Even for count, we need to process the result as it'll group some column together in sparse column families
        ResultSet rset = process(rows, variables, limit, now);
        rset = parameters.isCount ? rset.makeCountResult(parameters.countAlias) : rset;
        return new ResultMessage.Rows(rset);
    }

    static List<Row> readLocally(String keyspaceName, List<ReadCommand> cmds)
    {
        Keyspace keyspace = Keyspace.open(keyspaceName);
        List<Row> rows = new ArrayList<Row>(cmds.size());
        for (ReadCommand cmd : cmds)
            rows.add(cmd.getRow(keyspace));
        return rows;
    }

    public ResultMessage.Rows executeInternal(QueryState state) throws RequestExecutionException, RequestValidationException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.executeInternal(...)");
        
        List<ByteBuffer> variables = Collections.emptyList();
        int limit = getLimit(variables);
        long now = System.currentTimeMillis();
        List<Row> rows;
        if (isKeyRange || usesSecondaryIndexing)
        {
            RangeSliceCommand command = getRangeCommand(variables, limit, now);
            rows = command == null ? Collections.<Row>emptyList() : command.executeLocally();
        }
        else
        {
            List<ReadCommand> commands = getSliceCommands(variables, limit, now);
            rows = commands == null ? Collections.<Row>emptyList() : readLocally(keyspace(), commands);
        }

        return processResults(rows, variables, limit, now);
    }

    public ResultSet process(List<Row> rows) throws InvalidRequestException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.process(...1)");
        
        assert !parameters.isCount; // not yet needed
        return process(rows, Collections.<ByteBuffer>emptyList(), getLimit(Collections.<ByteBuffer>emptyList()), System.currentTimeMillis());
    }

    public String keyspace()
    {
        return cfm.ksName;
    }

    public String columnFamily()
    {
        return cfm.cfName;
    }

    private List<ReadCommand> getSliceCommands(List<ByteBuffer> variables, int limit, long now) throws RequestValidationException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.getSliceCommands(...)");
        
        Collection<ByteBuffer> keys = getKeys(variables);
        if (keys.isEmpty()) // in case of IN () for (the last column of) the partition key.
            return null;

        List<ReadCommand> commands = new ArrayList<ReadCommand>(keys.size());

        IDiskAtomFilter filter = makeFilter(variables, limit);
        if (filter == null)
            return null;

        // Note that we use the total limit for every key, which is potentially inefficient.
        // However, IN + LIMIT is not a very sensible choice.
        for (ByteBuffer key : keys)
        {
            QueryProcessor.validateKey(key);
            // We should not share the slice filter amongst the commands (hence the cloneShallow), due to
            // SliceQueryFilter not being immutable due to its columnCounter used by the lastCounted() method
            // (this is fairly ugly and we should change that but that's probably not a tiny refactor to do that cleanly)
            commands.add(ReadCommand.create(keyspace(), key, columnFamily(), now, filter.cloneShallow()));
        }

        return commands;
    }

    private RangeSliceCommand getRangeCommand(List<ByteBuffer> variables, int limit, long now) throws RequestValidationException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.getRangeCommand(...)");
        
        IDiskAtomFilter filter = makeFilter(variables, limit);
        if (filter == null)
            return null;

        List<IndexExpression> expressions = getIndexExpressions(variables);
        // The LIMIT provided by the user is the number of CQL row he wants returned.
        // We want to have getRangeSlice to count the number of columns, not the number of keys.
        AbstractBounds<RowPosition> keyBounds = getKeyBounds(variables);
        return keyBounds == null
             ? null
             : new RangeSliceCommand(keyspace(), columnFamily(), now,  filter, keyBounds, expressions, limit, !parameters.isDistinct, false);
    }

    private AbstractBounds<RowPosition> getKeyBounds(List<ByteBuffer> variables) throws InvalidRequestException
    {
        IPartitioner<?> p = StorageService.getPartitioner();

        if (onToken)
        {
            Token startToken = getTokenBound(Bound.START, variables, p);
            Token endToken = getTokenBound(Bound.END, variables, p);

            boolean includeStart = includeKeyBound(Bound.START);
            boolean includeEnd = includeKeyBound(Bound.END);

            /*
             * If we ask SP.getRangeSlice() for (token(200), token(200)], it will happily return the whole ring.
             * However, wrapping range doesn't really make sense for CQL, and we want to return an empty result
             * in that case (CASSANDRA-5573). So special case to create a range that is guaranteed to be empty.
             *
             * In practice, we want to return an empty result set if either startToken > endToken, or both are
             * equal but one of the bound is excluded (since [a, a] can contains something, but not (a, a], [a, a)
             * or (a, a)). Note though that in the case where startToken or endToken is the minimum token, then
             * this special case rule should not apply.
             */
            int cmp = startToken.compareTo(endToken);
            if (!startToken.isMinimum() && !endToken.isMinimum() && (cmp > 0 || (cmp == 0 && (!includeStart || !includeEnd))))
                return null;

            RowPosition start = includeStart ? startToken.minKeyBound() : startToken.maxKeyBound();
            RowPosition end = includeEnd ? endToken.maxKeyBound() : endToken.minKeyBound();

            return new Range<RowPosition>(start, end);
        }
        else
        {
            ByteBuffer startKeyBytes = getKeyBound(Bound.START, variables);
            ByteBuffer finishKeyBytes = getKeyBound(Bound.END, variables);

            RowPosition startKey = RowPosition.forKey(startKeyBytes, p);
            RowPosition finishKey = RowPosition.forKey(finishKeyBytes, p);

            if (startKey.compareTo(finishKey) > 0 && !finishKey.isMinimum(p))
                return null;

            if (includeKeyBound(Bound.START))
            {
                return includeKeyBound(Bound.END)
                     ? new Bounds<RowPosition>(startKey, finishKey)
                     : new IncludingExcludingBounds<RowPosition>(startKey, finishKey);
            }
            else
            {
                return includeKeyBound(Bound.END)
                     ? new Range<RowPosition>(startKey, finishKey)
                     : new ExcludingBounds<RowPosition>(startKey, finishKey);
            }
        }
    }

    private IDiskAtomFilter makeFilter(List<ByteBuffer> variables, int limit)
    throws InvalidRequestException
    {
        if (parameters.isDistinct)
        {
            return new SliceQueryFilter(ColumnSlice.ALL_COLUMNS_ARRAY, false, 1, -1);
        }
        else if (isColumnRange())
        {
            int toGroup = cfm.comparator.isDense() ? -1 : cfm.clusteringColumns().size();
            List<Composite> startBounds = getRequestedBound(Bound.START, variables);
            List<Composite> endBounds = getRequestedBound(Bound.END, variables);
            assert startBounds.size() == endBounds.size();

            // The case where startBounds == 1 is common enough that it's worth optimizing
            ColumnSlice[] slices;
            if (startBounds.size() == 1)
            {
                ColumnSlice slice = new ColumnSlice(startBounds.get(0), endBounds.get(0));
                if (slice.isAlwaysEmpty(cfm.comparator, isReversed))
                    return null;
                slices = new ColumnSlice[]{slice};
            }
            else
            {
                List<ColumnSlice> l = new ArrayList<ColumnSlice>(startBounds.size());
                for (int i = 0; i < startBounds.size(); i++)
                {
                    ColumnSlice slice = new ColumnSlice(startBounds.get(i), endBounds.get(i));
                    if (!slice.isAlwaysEmpty(cfm.comparator, isReversed))
                        l.add(slice);
                }
                if (l.isEmpty())
                    return null;
                slices = l.toArray(new ColumnSlice[l.size()]);
            }

            return new SliceQueryFilter(slices, isReversed, limit, toGroup);
        }
        else
        {
            SortedSet<CellName> cellNames = getRequestedColumns(variables);
            if (cellNames == null) // in case of IN () for the last column of the key
                return null;
            QueryProcessor.validateCellNames(cellNames);
            return new NamesQueryFilter(cellNames, true);
        }
    }

    private int getLimit(List<ByteBuffer> variables) throws InvalidRequestException
    {
        int l = Integer.MAX_VALUE;
        if (limit != null)
        {
            ByteBuffer b = limit.bindAndGet(variables);
            if (b == null)
                throw new InvalidRequestException("Invalid null value of limit");

            try
            {
                Int32Type.instance.validate(b);
                l = Int32Type.instance.compose(b);
            }
            catch (MarshalException e)
            {
                throw new InvalidRequestException("Invalid limit value");
            }
        }

        if (l <= 0)
            throw new InvalidRequestException("LIMIT must be strictly positive");

        // Internally, we don't support exclusive bounds for slices. Instead,
        // we query one more element if necessary and exclude
        if (sliceRestriction != null && (!sliceRestriction.isInclusive(Bound.START) || !sliceRestriction.isInclusive(Bound.END)) && l != Integer.MAX_VALUE)
            l += 1;

        return l;
    }

    private Collection<ByteBuffer> getKeys(final List<ByteBuffer> variables) throws InvalidRequestException
    {
        List<ByteBuffer> keys = new ArrayList<ByteBuffer>();
        CBuilder builder = cfm.getKeyValidatorAsCType().builder();
        for (ColumnDefinition def : cfm.partitionKeyColumns())
        {
            Restriction r = keyRestrictions[def.position()];
            assert r != null && !r.isSlice();

            List<ByteBuffer> values = r.values(variables);

            if (builder.remainingCount() == 1)
            {
                for (ByteBuffer val : values)
                {
                    if (val == null)
                        throw new InvalidRequestException(String.format("Invalid null value for partition key part %s", def.name));
                    keys.add(builder.buildWith(val).toByteBuffer());
                }
            }
            else
            {
                // Note: for backward compatibility reasons, we let INs with 1 value slide
                if (values.size() != 1)
                    throw new InvalidRequestException("IN is only supported on the last column of the partition key");
                ByteBuffer val = values.get(0);
                if (val == null)
                    throw new InvalidRequestException(String.format("Invalid null value for partition key part %s", def.name));
                builder.add(val);
            }
        }
        return keys;
    }

    private ByteBuffer getKeyBound(Bound b, List<ByteBuffer> variables) throws InvalidRequestException
    {
        // Deal with unrestricted partition key components (special-casing is required to deal with 2i queries on the first
        // component of a composite partition key).
        for (int i = 0; i < keyRestrictions.length; i++)
            if (keyRestrictions[i] == null)
                return ByteBufferUtil.EMPTY_BYTE_BUFFER;

        // We deal with IN queries for keys in other places, so we know buildBound will return only one result
        return buildBound(b, cfm.partitionKeyColumns(), keyRestrictions, false, cfm.getKeyValidatorAsCType(), variables).get(0).toByteBuffer();
    }

    private Token getTokenBound(Bound b, List<ByteBuffer> variables, IPartitioner<?> p) throws InvalidRequestException
    {
        assert onToken;

        Restriction keyRestriction = keyRestrictions[0];
        ByteBuffer value;
        if (keyRestriction.isEQ())
        {
            value = keyRestriction.values(variables).get(0);
        }
        else
        {
            Restriction.Slice slice = (Restriction.Slice)keyRestriction;
            if (!slice.hasBound(b))
                return p.getMinimumToken();

            value = slice.bound(b, variables);
        }

        if (value == null)
            throw new InvalidRequestException("Invalid null token value");
        return p.getTokenFactory().fromByteArray(value);
    }

    private boolean includeKeyBound(Bound b)
    {
        for (Restriction r : keyRestrictions)
        {
            if (r == null)
                return true;
            else if (r.isSlice())
                return ((Restriction.Slice)r).isInclusive(b);
        }
        // All equality
        return true;
    }

    private boolean isColumnRange()
    {
        // Due to CASSANDRA-5762, we always do a slice for CQL3 tables (not dense, composite).
        // Static CF (non dense but non composite) never entails a column slice however
        if (!cfm.comparator.isDense())
            return cfm.comparator.isCompound();

        // Otherwise (i.e. for compact table where we don't have a row marker anyway and thus don't care about CASSANDRA-5762),
        // it is a range query if it has at least one the column alias for which no relation is defined or is not EQ.
        for (Restriction r : columnRestrictions)
        {
            if (r == null || r.isSlice())
                return true;
        }
        return false;
    }

    private SortedSet<CellName> getRequestedColumns(List<ByteBuffer> variables) throws InvalidRequestException
    {
        assert !isColumnRange();

        CBuilder builder = cfm.comparator.prefixBuilder();
        Iterator<ColumnDefinition> idIter = cfm.clusteringColumns().iterator();
        for (Restriction r : columnRestrictions)
        {
            ColumnDefinition def = idIter.next();
            assert r != null && !r.isSlice();

            List<ByteBuffer> values = r.values(variables);
            if (values.size() == 1)
            {
                ByteBuffer val = values.get(0);
                if (val == null)
                    throw new InvalidRequestException(String.format("Invalid null value for clustering key part %s", def.name));
                builder.add(val);
            }
            else
            {
                // We have a IN, which we only support for the last column.
                // If compact, just add all values and we're done. Otherwise,
                // for each value of the IN, creates all the columns corresponding to the selection.
                if (values.isEmpty())
                    return null;
                SortedSet<CellName> columns = new TreeSet<CellName>(cfm.comparator);
                Iterator<ByteBuffer> iter = values.iterator();
                while (iter.hasNext())
                {
                    ByteBuffer val = iter.next();
                    if (val == null)
                        throw new InvalidRequestException(String.format("Invalid null value for clustering key part %s", def.name));

                    Composite prefix = builder.buildWith(val);
                    columns.addAll(addSelectedColumns(prefix));
                }
                return columns;
            }
        }

        return addSelectedColumns(builder.build());
    }

    private SortedSet<CellName> addSelectedColumns(Composite prefix)
    {
        if (cfm.comparator.isDense())
        {
            return FBUtilities.singleton(cfm.comparator.create(prefix, null), cfm.comparator);
        }
        else
        {
            // Collections require doing a slice query because a given collection is a
            // non-know set of columns, so we shouldn't get there
            assert !selectACollection();

            SortedSet<CellName> columns = new TreeSet<CellName>(cfm.comparator);

            // We need to query the selected column as well as the marker
            // column (for the case where the row exists but has no columns outside the PK)
            // Two exceptions are "static CF" (non-composite non-compact CF) and "super CF"
            // that don't have marker and for which we must query all columns instead
            if (cfm.comparator.isCompound() && !cfm.isSuper())
            {
                // marker
                columns.add(cfm.comparator.rowMarker(prefix));

                // selected columns
                for (ColumnDefinition def : selection.getColumnsList())
                    if (def.kind == ColumnDefinition.Kind.REGULAR)
                        columns.add(cfm.comparator.create(prefix, def.name));
            }
            else
            {
                for (ColumnDefinition def : cfm.regularColumns())
                    columns.add(cfm.comparator.create(prefix, def.name));
            }
            return columns;
        }
    }

    private boolean selectACollection()
    {
        if (!cfm.comparator.hasCollections())
            return false;

        for (ColumnDefinition def : selection.getColumnsList())
        {
            if (def.type instanceof CollectionType)
                return true;
        }

        return false;
    }

    private static List<Composite> buildBound(Bound bound,
                                              Collection<ColumnDefinition> defs,
                                              Restriction[] restrictions,
                                              boolean isReversed,
                                              CType type,
                                              List<ByteBuffer> variables) throws InvalidRequestException
    {
        CBuilder builder = type.builder();

        // The end-of-component of composite doesn't depend on whether the
        // component type is reversed or not (i.e. the ReversedType is applied
        // to the component comparator but not to the end-of-component itself),
        // it only depends on whether the slice is reversed
        Bound eocBound = isReversed ? Bound.reverse(bound) : bound;
        for (ColumnDefinition def : defs)
        {
            // In a restriction, we always have Bound.START < Bound.END for the "base" comparator.
            // So if we're doing a reverse slice, we must inverse the bounds when giving them as start and end of the slice filter.
            // But if the actual comparator itself is reversed, we must inversed the bounds too.
            Bound b = isReversed == isReversedType(def) ? bound : Bound.reverse(bound);
            Restriction r = restrictions[def.position()];
            if (r == null || (r.isSlice() && !((Restriction.Slice)r).hasBound(b)))
            {
                // There wasn't any non EQ relation on that key, we select all records having the preceding component as prefix.
                // For composites, if there was preceding component and we're computing the end, we must change the last component
                // End-Of-Component, otherwise we would be selecting only one record.
                Composite prefix = builder.build();
                return Collections.singletonList(!prefix.isEmpty() && eocBound == Bound.END ? prefix.end() : prefix);
            }

            if (r.isSlice())
            {
                Restriction.Slice slice = (Restriction.Slice)r;
                assert slice.hasBound(b);
                ByteBuffer val = slice.bound(b, variables);
                if (val == null)
                    throw new InvalidRequestException(String.format("Invalid null clustering key part %s", def.name));
                return Collections.singletonList(builder.add(val).build().withEOC(eocForRelation(slice.getRelation(eocBound, b))));
            }
            else
            {
                List<ByteBuffer> values = r.values(variables);
                if (values.size() != 1)
                {
                    // IN query, we only support it on the clustering column
                    assert def.position() == defs.size() - 1;
                    // The IN query might not have listed the values in comparator order, so we need to re-sort
                    // the bounds lists to make sure the slices works correctly (also, to avoid duplicates).
                    TreeSet<Composite> s = new TreeSet<Composite>(isReversed ? type.reverseComparator() : type);
                    for (ByteBuffer val : values)
                    {
                        if (val == null)
                            throw new InvalidRequestException(String.format("Invalid null clustering key part %s", def.name));
                        Composite prefix = builder.buildWith(val);
                        // See below for why this
                        s.add((bound == Bound.END && builder.remainingCount() > 0) ? prefix.end() : prefix);
                    }
                    return new ArrayList<Composite>(s);
                }

                ByteBuffer val = values.get(0);
                if (val == null)
                    throw new InvalidRequestException(String.format("Invalid null clustering key part %s", def.name));
                builder.add(val);
            }
        }
        // Means no relation at all or everything was an equal
        // Note: if the builder is "full", there is no need to use the end-of-component bit. For columns selection,
        // it would be harmless to do it. However, we use this method got the partition key too. And when a query
        // with 2ndary index is done, and with the the partition provided with an EQ, we'll end up here, and in that
        // case using the eoc would be bad, since for the random partitioner we have no guarantee that
        // prefix.end() will sort after prefix (see #5240).
        Composite prefix = builder.build();
        return Collections.singletonList(bound == Bound.END && builder.remainingCount() > 0 ? prefix.end() : prefix);
    }

    private static Composite.EOC eocForRelation(Relation.Type op)
    {
        switch (op)
        {
            case LT:
                // < X => using startOf(X) as finish bound
                return Composite.EOC.START;
            case GT:
            case LTE:
                // > X => using endOf(X) as start bound
                // <= X => using endOf(X) as finish bound
                return Composite.EOC.END;
            default:
                // >= X => using X as start bound (could use START_OF too)
                // = X => using X
                return Composite.EOC.NONE;
        }
    }

    private List<Composite> getRequestedBound(Bound b, List<ByteBuffer> variables) throws InvalidRequestException
    {
        assert isColumnRange();
        return buildBound(b, cfm.clusteringColumns(), columnRestrictions, isReversed, cfm.comparator, variables);
    }

    public List<IndexExpression> getIndexExpressions(List<ByteBuffer> variables) throws InvalidRequestException
    {
        if (!usesSecondaryIndexing || restrictedColumns.isEmpty())
            return Collections.emptyList();

        List<IndexExpression> expressions = new ArrayList<IndexExpression>();
        for (ColumnDefinition def : restrictedColumns)
        {
            Restriction restriction;
            switch (def.kind)
            {
                case PARTITION_KEY:
                    restriction = keyRestrictions[def.position()];
                    break;
                case CLUSTERING_COLUMN:
                    restriction = columnRestrictions[def.position()];
                    break;
                case REGULAR:
                    restriction = metadataRestrictions.get(def.name);
                    break;
                default:
                    // We don't allow restricting a COMPACT_VALUE for now in prepare.
                    throw new AssertionError();
            }

            if (restriction.isSlice())
            {
                Restriction.Slice slice = (Restriction.Slice)restriction;
                for (Bound b : Bound.values())
                {
                    if (slice.hasBound(b))
                    {
                        ByteBuffer value = validateIndexedValue(def, slice.bound(b, variables));
                        expressions.add(new IndexExpression(def.name.bytes, slice.getIndexOperator(b), value));
                    }
                }
            }
            else if (restriction.isContains())
            {
                Restriction.Contains contains = (Restriction.Contains)restriction;
                for (ByteBuffer value : contains.values(variables))
                {
                    validateIndexedValue(def, value);
                    expressions.add(new IndexExpression(def.name.bytes, IndexExpression.Operator.CONTAINS, value));
                }
                for (ByteBuffer key : contains.keys(variables))
                {
                    validateIndexedValue(def, key);
                    expressions.add(new IndexExpression(def.name.bytes, IndexExpression.Operator.CONTAINS_KEY, key));
                }
            }
            else
            {
                List<ByteBuffer> values = restriction.values(variables);

                if (values.size() != 1)
                    throw new InvalidRequestException("IN restrictions are not supported on indexed columns");

                ByteBuffer value = validateIndexedValue(def, values.get(0));
                expressions.add(new IndexExpression(def.name.bytes, IndexExpression.Operator.EQ, value));
            }
        }
        return expressions;
    }

    private static ByteBuffer validateIndexedValue(ColumnDefinition def, ByteBuffer value) throws InvalidRequestException
    {
        if (value == null)
            throw new InvalidRequestException(String.format("Unsupported null value for indexed column %s", def.name));
        if (value.remaining() > 0xFFFF)
            throw new InvalidRequestException("Index expression values may not be larger than 64K");
        return value;
    }

    private Iterator<Cell> applySliceRestriction(final Iterator<Cell> cells, final List<ByteBuffer> variables) throws InvalidRequestException
    {
        assert sliceRestriction != null;

        final CellNameType type = cfm.comparator;
        final CellName excludedStart = sliceRestriction.isInclusive(Bound.START) ? null : type.makeCellName(sliceRestriction.bound(Bound.START, variables));
        final CellName excludedEnd = sliceRestriction.isInclusive(Bound.END) ? null : type.makeCellName(sliceRestriction.bound(Bound.END, variables));

        return new AbstractIterator<Cell>()
        {
            protected Cell computeNext()
            {
                while (cells.hasNext())
                {
                    Cell c = cells.next();

                    // For dynamic CF, the column could be out of the requested bounds (because we don't support strict bounds internally (unless
                    // the comparator is composite that is)), filter here
                    if ( (excludedStart != null && type.compare(c.name(), excludedStart) == 0)
                      || (excludedEnd != null && type.compare(c.name(), excludedEnd) == 0) )
                        continue;

                    return c;
                }
                return endOfData();
            }
        };
    }

    private ResultSet process(List<Row> rows, List<ByteBuffer> variables, int limit, long now) throws InvalidRequestException
    {
        
        logger.info(">>> STRATIO >>> SelectStatement.process(...)");
        
        Selection.ResultSetBuilder result = selection.resultSetBuilder(now);
        for (org.apache.cassandra.db.Row row : rows)
        {
            // Not columns match the query, skip
            if (row.cf == null)
                continue;

            processColumnFamily(row.key.key, row.cf, variables, now, result);
        }

        ResultSet cqlRows = result.build();

        orderResults(cqlRows, variables);

        // Internal calls always return columns in the comparator order, even when reverse was set
        if (isReversed)
            cqlRows.reverse();

        // Trim result if needed to respect the limit
        cqlRows.trim(limit);
        return cqlRows;
    }

    // Used by ModificationStatement for CAS operations
    void processColumnFamily(ByteBuffer key, ColumnFamily cf, List<ByteBuffer> variables, long now, Selection.ResultSetBuilder result)
    throws InvalidRequestException
    {
        CFMetaData cfm = cf.metadata();
        ByteBuffer[] keyComponents = null;
        if (cfm.getKeyValidator() instanceof CompositeType)
        {
            keyComponents = ((CompositeType)cfm.getKeyValidator()).split(key);
        }
        else
        {
            keyComponents = new ByteBuffer[]{ key };
        }

        Iterator<Cell> cells = cf.getSortedColumns().iterator();
        if (sliceRestriction != null)
            cells = applySliceRestriction(cells, variables);

        for (Iterator<CQL3Row> iter = cfm.comparator.CQL3RowBuilder(now).group(cells); iter.hasNext();)
        {
            CQL3Row cql3Row = iter.next();

            // Respect requested order
            result.newRow();
            // Respect selection order
            for (ColumnDefinition def : selection.getColumnsList())
            {
                switch (def.kind)
                {
                    case PARTITION_KEY:
                        result.add(keyComponents[def.position()]);
                        break;
                    case CLUSTERING_COLUMN:
                        result.add(cql3Row.getClusteringColumn(def.position()));
                        break;
                    case COMPACT_VALUE:
                        result.add(cql3Row.getColumn(null));
                        break;
                    case REGULAR:
                        if (def.type.isCollection())
                        {
                            List<Cell> collection = cql3Row.getCollection(def.name);
                            ByteBuffer value = collection == null
                                             ? null
                                             : ((CollectionType)def.type).serialize(collection);
                            result.add(value);
                        }
                        else
                        {
                            result.add(cql3Row.getColumn(def.name));
                        }
                        break;
                    }
                }
        }
    }

    /**
     * Orders results when multiple keys are selected (using IN)
     */
    private void orderResults(ResultSet cqlRows, List<ByteBuffer> variables) throws InvalidRequestException
    {
        if (cqlRows.size() == 0)
            return;

        /*
         * We need to do post-query ordering in 2 cases:
         *   1) if the last clustering key is restricted by a IN.
         *   2) if the row key is restricted by a IN and there is some ORDER BY values
         */
        if (!(lastClusteringIsIn || (keyIsInRelation && parameters.orderings.size() > 0)))
            return;

        assert orderingIndexes != null;

        List<Integer> idToSort = new ArrayList<Integer>();
        List<Comparator<ByteBuffer>> sorters = new ArrayList<Comparator<ByteBuffer>>();

        // If the restriction for the last clustering key is an IN, respect requested order
        if (lastClusteringIsIn)
        {
            List<ColumnDefinition> cc = cfm.clusteringColumns();
            idToSort.add(orderingIndexes.get(cc.get(cc.size() - 1).name));
            Restriction last = columnRestrictions[columnRestrictions.length - 1];
            sorters.add(makeComparatorFor(last.values(variables)));
        }

        // Then add the order by
        for (ColumnIdentifier identifier : parameters.orderings.keySet())
        {
            ColumnDefinition orderingColumn = cfm.getColumnDefinition(identifier);
            idToSort.add(orderingIndexes.get(orderingColumn.name));
            sorters.add(orderingColumn.type);
        }

        Comparator<List<ByteBuffer>> comparator = idToSort.size() == 1
                                                ? new SingleColumnComparator(idToSort.get(0), sorters.get(0))
                                                : new CompositeComparator(sorters, idToSort);
        Collections.sort(cqlRows.rows, comparator);
    }

    // Comparator used when the last clustering key is an IN, to sort result
    // rows in the order of the values provided for the IN.
    private Comparator<ByteBuffer> makeComparatorFor(final List<ByteBuffer> values)
    {
        // This may not always be the most efficient, but it probably is if
        // values is small, which is likely to be the most common case.
        return new Comparator<ByteBuffer>()
        {
            public int compare(ByteBuffer b1, ByteBuffer b2)
            {
                int idx1 = -1;
                int idx2 = -1;
                for (int i = 0; i < values.size(); i++)
                {
                    ByteBuffer bb = values.get(i);
                    if (bb.equals(b1))
                        idx1 = i;
                    if (bb.equals(b2))
                        idx2 = i;

                    if (idx1 >= 0 && idx2 >= 0)
                        break;
                }
                assert idx1 >= 0 && idx2 >= 0 : "Got CQL3 row that was not queried in resultset";
                return idx1 - idx2;
            }
        };
    }

    private static boolean isReversedType(ColumnDefinition def)
    {
        return def.type instanceof ReversedType;
    }

    private boolean columnFilterIsIdentity()
    {
        for (Restriction r : columnRestrictions)
        {
            if (r != null)
                return false;
        }
        return true;
    }

    public static class RawStatement extends CFStatement
    {
        private final Parameters parameters;
        private final List<RawSelector> selectClause;
        private final List<Relation> whereClause;
        private final Term.Raw limit;

        public RawStatement(CFName cfName, Parameters parameters, List<RawSelector> selectClause, List<Relation> whereClause, Term.Raw limit)
        {
            super(cfName);
            this.parameters = parameters;
            this.selectClause = selectClause;
            this.whereClause = whereClause == null ? Collections.<Relation>emptyList() : whereClause;
            this.limit = limit;
        }

        public ParsedStatement.Prepared prepare() throws InvalidRequestException
        {
            CFMetaData cfm = ThriftValidation.validateColumnFamily(keyspace(), columnFamily());

            VariableSpecifications names = getBoundVariables();

            // Select clause
            if (parameters.isCount && !selectClause.isEmpty())
                throw new InvalidRequestException("Only COUNT(*) and COUNT(1) operations are currently supported.");

            Selection selection = selectClause.isEmpty()
                                ? Selection.wildcard(cfm)
                                : Selection.fromSelectors(cfm, selectClause);

            if (parameters.isDistinct)
                validateDistinctSelection(selection.getColumnsList(), cfm.partitionKeyColumns());

            Term prepLimit = null;
            if (limit != null)
            {
                prepLimit = limit.prepare(keyspace(), limitReceiver());
                prepLimit.collectMarkerSpecification(names);
            }

            SelectStatement stmt = new SelectStatement(cfm, names.size(), parameters, selection, prepLimit);

            /*
             * WHERE clause. For a given entity, rules are:
             *   - EQ relation conflicts with anything else (including a 2nd EQ)
             *   - Can't have more than one LT(E) relation (resp. GT(E) relation)
             *   - IN relation are restricted to row keys (for now) and conflicts with anything else
             *     (we could allow two IN for the same entity but that doesn't seem very useful)
             *   - The value_alias cannot be restricted in any way (we don't support wide rows with indexed value in CQL so far)
             */
            boolean hasQueriableIndex = false;
            boolean hasQueriableClusteringColumnIndex = false;
            for (Relation rel : whereClause)
            {
                ColumnDefinition def = cfm.getColumnDefinition(rel.getEntity());
                if (def == null)
                {
                    if (containsAlias(rel.getEntity()))
                        throw new InvalidRequestException(String.format("Aliases aren't allowed in where clause ('%s')", rel));
                    else
                        throw new InvalidRequestException(String.format("Undefined name %s in where clause ('%s')", rel.getEntity(), rel));
                }

                stmt.restrictedColumns.add(def);
                if (def.isIndexed() && rel.operator().allowsIndexQuery())
                {
                    hasQueriableIndex = true;
                    if (def.kind == ColumnDefinition.Kind.CLUSTERING_COLUMN)
                        hasQueriableClusteringColumnIndex = true;
                }

                switch (def.kind)
                {
                    case PARTITION_KEY:
                        stmt.keyRestrictions[def.position()] = updateRestriction(def, stmt.keyRestrictions[def.position()], rel, names);
                        break;
                    case CLUSTERING_COLUMN:
                        stmt.columnRestrictions[def.position()] = updateRestriction(def, stmt.columnRestrictions[def.position()], rel, names);
                        break;
                    case COMPACT_VALUE:
                        throw new InvalidRequestException(String.format("Predicates on the non-primary-key column (%s) of a COMPACT table are not yet supported", def.name));
                    case REGULAR:
                        // We only all IN on the row key and last clustering key so far, never on non-PK columns, and this even if there's an index
                        Restriction r = updateRestriction(def, stmt.metadataRestrictions.get(def.name), rel, names);
                        if (r.isIN() && !((Restriction.IN)r).canHaveOnlyOneValue())
                            // Note: for backward compatibility reason, we conside a IN of 1 value the same as a EQ, so we let that slide.
                            throw new InvalidRequestException(String.format("IN predicates on non-primary-key columns (%s) is not yet supported", def.name));
                        stmt.metadataRestrictions.put(def.name, r);
                        break;
                }
            }

            /*
             * At this point, the select statement if fully constructed, but we still have a few things to validate
             */

            // If there is a queriable index, no special condition are required on the other restrictions.
            // But we still need to know 2 things:
            //   - If we don't have a queriable index, is the query ok
            //   - Is it queriable without 2ndary index, which is always more efficient
            // If a component of the partition key is restricted by a relation, all preceding
            // components must have a EQ. Only the last partition key component can be in IN relation.
            boolean canRestrictFurtherComponents = true;
            ColumnDefinition previous = null;
            stmt.keyIsInRelation = false;
            Iterator<ColumnDefinition> iter = cfm.partitionKeyColumns().iterator();
            for (int i = 0; i < stmt.keyRestrictions.length; i++)
            {
                ColumnDefinition cdef = iter.next();
                Restriction restriction = stmt.keyRestrictions[i];

                if (restriction == null)
                {
                    if (stmt.onToken)
                        throw new InvalidRequestException("The token() function must be applied to all partition key components or none of them");

                    // The only time not restricting a key part is allowed is if none are restricted or an index is used.
                    if (i > 0 && stmt.keyRestrictions[i - 1] != null)
                    {
                        if (hasQueriableIndex)
                        {
                            stmt.usesSecondaryIndexing = true;
                            stmt.isKeyRange = true;
                            break;
                        }
                        throw new InvalidRequestException(String.format("Partition key part %s must be restricted since preceding part is", cdef.name));
                    }

                    stmt.isKeyRange = true;
                    canRestrictFurtherComponents = false;
                }
                else if (!canRestrictFurtherComponents)
                {
                    if (hasQueriableIndex)
                    {
                        stmt.usesSecondaryIndexing = true;
                        break;
                    }
                    throw new InvalidRequestException(String.format("partition key part %s cannot be restricted (preceding part %s is either not restricted or by a non-EQ relation)", cdef.name, previous));
                }
                else if (restriction.isOnToken())
                {
                    // If this is a query on tokens, it's necessarily a range query (there can be more than one key per token).
                    stmt.isKeyRange = true;
                    stmt.onToken = true;
                }
                else if (stmt.onToken)
                {
                    throw new InvalidRequestException(String.format("The token() function must be applied to all partition key components or none of them"));
                }
                else if (!restriction.isSlice())
                {
                    if (restriction.isIN())
                    {
                        // We only support IN for the last name so far
                        if (i != stmt.keyRestrictions.length - 1)
                            throw new InvalidRequestException(String.format("Partition KEY part %s cannot be restricted by IN relation (only the last part of the partition key can)", cdef.name));
                        stmt.keyIsInRelation = true;
                    }
                }
                else
                {
                    // Non EQ relation is not supported without token(), even if we have a 2ndary index (since even those are ordered by partitioner).
                    // Note: In theory we could allow it for 2ndary index queries with ALLOW FILTERING, but that would probably require some special casing
                    throw new InvalidRequestException("Only EQ and IN relation are supported on the partition key (unless you use the token() function)");
                }
                previous = cdef;
            }

            // All (or none) of the partition key columns have been specified;
            // hence there is no need to turn these restrictions into index expressions.
            if (!stmt.usesSecondaryIndexing)
                stmt.restrictedColumns.removeAll(cfm.partitionKeyColumns());

            // If a clustering key column is restricted by a non-EQ relation, all preceding
            // columns must have a EQ, and all following must have no restriction. Unless
            // the column is indexed that is.
            canRestrictFurtherComponents = true;
            previous = null;
            iter = cfm.clusteringColumns().iterator();
            for (int i = 0; i < stmt.columnRestrictions.length; i++)
            {
                ColumnDefinition cdef = iter.next();
                Restriction restriction = stmt.columnRestrictions[i];

                if (restriction == null)
                {
                    canRestrictFurtherComponents = false;
                }
                else if (!canRestrictFurtherComponents)
                {
                    if (hasQueriableIndex)
                    {
                        stmt.usesSecondaryIndexing = true; // handle gaps and non-keyrange cases.
                        break;
                    }
                    throw new InvalidRequestException(String.format("PRIMARY KEY part %s cannot be restricted (preceding part %s is either not restricted or by a non-EQ relation)", cdef.name, previous));
                }
                else if (restriction.isSlice())
                {
                    canRestrictFurtherComponents = false;
                    Restriction.Slice slice = (Restriction.Slice)restriction;
                    // For non-composite slices, we don't support internally the difference between exclusive and
                    // inclusive bounds, so we deal with it manually.
                    if (!cfm.comparator.isCompound() && (!slice.isInclusive(Bound.START) || !slice.isInclusive(Bound.END)))
                        stmt.sliceRestriction = slice;
                }
                else if (restriction.isIN())
                {
                    // We only support IN for the last name and for compact storage so far
                    // TODO: #3885 allows us to extend to non compact as well, but that remains to be done
                    if (i != stmt.columnRestrictions.length - 1)
                        throw new InvalidRequestException(String.format("PRIMARY KEY part %s cannot be restricted by IN relation", cdef.name));
                    else if (stmt.selectACollection())
                        throw new InvalidRequestException(String.format("Cannot restrict PRIMARY KEY part %s by IN relation as a collection is selected by the query", cdef.name));
                    stmt.lastClusteringIsIn = true;
                }

                previous = cdef;
            }

            // Covers indexes on the first clustering column (among others).
            if (stmt.isKeyRange && hasQueriableClusteringColumnIndex)
                stmt.usesSecondaryIndexing = true;

            if (!stmt.usesSecondaryIndexing)
                stmt.restrictedColumns.removeAll(cfm.clusteringColumns());

            // Even if usesSecondaryIndexing is false at this point, we'll still have to use one if
            // there is restrictions not covered by the PK.
            if (!stmt.metadataRestrictions.isEmpty())
            {
                if (!hasQueriableIndex)
                    throw new InvalidRequestException("No indexed columns present in by-columns clause with Equal operator");
                stmt.usesSecondaryIndexing = true;
            }

            if (stmt.usesSecondaryIndexing && stmt.keyIsInRelation)
                throw new InvalidRequestException("Select on indexed columns and with IN clause for the PRIMARY KEY are not supported");

            if (!stmt.parameters.orderings.isEmpty())
            {
                if (stmt.usesSecondaryIndexing)
                    throw new InvalidRequestException("ORDER BY with 2ndary indexes is not supported.");

                if (stmt.isKeyRange)
                    throw new InvalidRequestException("ORDER BY is only supported when the partition key is restricted by an EQ or an IN.");

                // If we order an IN query, we'll have to do a manual sort post-query. Currently, this sorting requires that we
                // have queried the column on which we sort (TODO: we should update it to add the column on which we sort to the one
                // queried automatically, and then removing it from the resultSet afterwards if needed)
                if (stmt.keyIsInRelation)
                {
                    stmt.orderingIndexes = new HashMap<ColumnIdentifier, Integer>();
                    for (ColumnIdentifier column : stmt.parameters.orderings.keySet())
                    {
                        final ColumnDefinition def = cfm.getColumnDefinition(column);
                        if (def == null)
                        {
                            if (containsAlias(column))
                                throw new InvalidRequestException(String.format("Aliases are not allowed in order by clause ('%s')", column));
                            else
                                throw new InvalidRequestException(String.format("Order by on unknown column %s", column));
                        }

                        if (selectClause.isEmpty()) // wildcard
                        {
                            stmt.orderingIndexes.put(def.name, indexOf(def, cfm.allColumnsInSelectOrder()));
                        }
                        else
                        {
                            boolean hasColumn = false;
                            for (int i = 0; i < selectClause.size(); i++)
                            {
                                RawSelector selector = selectClause.get(i);
                                if (def.name.equals(selector.selectable))
                                {
                                    stmt.orderingIndexes.put(def.name, i);
                                    hasColumn = true;
                                    break;
                                }
                            }

                            if (!hasColumn)
                                throw new InvalidRequestException("ORDER BY could not be used on columns missing in select clause.");
                        }
                    }
                }

                Boolean[] reversedMap = new Boolean[cfm.clusteringColumns().size()];
                int i = 0;
                for (Map.Entry<ColumnIdentifier, Boolean> entry : stmt.parameters.orderings.entrySet())
                {
                    ColumnIdentifier column = entry.getKey();
                    boolean reversed = entry.getValue();

                    ColumnDefinition def = cfm.getColumnDefinition(column);
                    if (def == null)
                    {
                        if (containsAlias(column))
                            throw new InvalidRequestException(String.format("Aliases are not allowed in order by clause ('%s')", column));
                        else
                            throw new InvalidRequestException(String.format("Order by on unknown column %s", column));
                    }

                    if (def.kind != ColumnDefinition.Kind.CLUSTERING_COLUMN)
                        throw new InvalidRequestException(String.format("Order by is currently only supported on the clustered columns of the PRIMARY KEY, got %s", column));

                    if (i++ != def.position())
                        throw new InvalidRequestException(String.format("Order by currently only support the ordering of columns following their declared order in the PRIMARY KEY"));

                    reversedMap[def.position()] = (reversed != isReversedType(def));
                }

                // Check that all boolean in reversedMap, if set, agrees
                Boolean isReversed = null;
                for (Boolean b : reversedMap)
                {
                    // Cell on which order is specified can be in any order
                    if (b == null)
                        continue;

                    if (isReversed == null)
                    {
                        isReversed = b;
                        continue;
                    }
                    if (isReversed != b)
                        throw new InvalidRequestException(String.format("Unsupported order by relation"));
                }
                assert isReversed != null;
                stmt.isReversed = isReversed;
            }

            if (stmt.lastClusteringIsIn)
            {
                // This means we'll have to do post-query reordering, so update the orderingIndexes
                if (stmt.orderingIndexes == null)
                    stmt.orderingIndexes = new HashMap<ColumnIdentifier, Integer>();

                ColumnDefinition last = cfm.clusteringColumns().get(cfm.clusteringColumns().size() - 1);
                stmt.orderingIndexes.put(last.name, indexOf(last, stmt.selection.getColumnsList().iterator()));
            }

            // Make sure this queries is allowed (note: non key range non indexed cannot involve filtering underneath)
            if (!parameters.allowFiltering && (stmt.isKeyRange || stmt.usesSecondaryIndexing))
            {
                // We will potentially filter data if either:
                //  - Have more than one IndexExpression
                //  - Have no index expression and the column filter is not the identity
                if (stmt.restrictedColumns.size() > 1 || (stmt.restrictedColumns.isEmpty() && !stmt.columnFilterIsIdentity()))
                    throw new InvalidRequestException("Cannot execute this query as it might involve data filtering and thus may have unpredictable performance. "
                                                    + "If you want to execute this query despite the performance unpredictability, use ALLOW FILTERING");
            }

            return new ParsedStatement.Prepared(stmt, names);
        }

        private int indexOf(final ColumnDefinition def, Iterator<ColumnDefinition> defs)
        {
            return Iterators.indexOf(defs, new Predicate<ColumnDefinition>()
                                           {
                                               public boolean apply(ColumnDefinition n)
                                               {
                                                   return def.name.equals(n.name);
                                               }
                                           });
        }

        private void validateDistinctSelection(Collection<ColumnDefinition> requestedColumns, Collection<ColumnDefinition> partitionKey)
        throws InvalidRequestException
        {
            for (ColumnDefinition def : requestedColumns)
                if (!partitionKey.contains(def))
                    throw new InvalidRequestException(String.format("SELECT DISTINCT queries must only request partition key columns (not %s)", def.name));

            for (ColumnDefinition def : partitionKey)
                if (!requestedColumns.contains(def))
                    throw new InvalidRequestException(String.format("SELECT DISTINCT queries must request all the partition key columns (missing %s)", def.name));
        }

        private boolean containsAlias(final ColumnIdentifier name)
        {
            return Iterables.any(selectClause, new Predicate<RawSelector>()
                                               {
                                                   public boolean apply(RawSelector raw)
                                                   {
                                                       return name.equals(raw.alias);
                                                   }
                                               });
        }

        private ColumnSpecification limitReceiver()
        {
            return new ColumnSpecification(keyspace(), columnFamily(), new ColumnIdentifier("[limit]", true), Int32Type.instance);
        }

        Restriction updateRestriction(ColumnDefinition def, Restriction restriction, Relation newRel, VariableSpecifications boundNames) throws InvalidRequestException
        {
            ColumnSpecification receiver = def;
            if (newRel.onToken)
            {
                if (def.kind != ColumnDefinition.Kind.PARTITION_KEY)
                    throw new InvalidRequestException(String.format("The token() function is only supported on the partition key, found on %s", def.name));

                receiver = new ColumnSpecification(def.ksName,
                                                   def.cfName,
                                                   new ColumnIdentifier("partition key token", true),
                                                   StorageService.getPartitioner().getTokenValidator());
            }

            switch (newRel.operator())
            {
                case EQ:
                    {
                        if (restriction != null)
                            throw new InvalidRequestException(String.format("%s cannot be restricted by more than one relation if it includes an Equal", def.name));
                        Term t = newRel.getValue().prepare(keyspace(), receiver);
                        t.collectMarkerSpecification(boundNames);
                        restriction = new Restriction.EQ(t, newRel.onToken);
                    }
                    break;
                case IN:
                    if (restriction != null)
                        throw new InvalidRequestException(String.format("%s cannot be restricted by more than one relation if it includes a IN", def.name));

                    if (newRel.getInValues() == null)
                    {
                        // Means we have a "SELECT ... IN ?"
                        assert newRel.getValue() != null;
                        Term t = newRel.getValue().prepare(keyspace(), receiver);
                        t.collectMarkerSpecification(boundNames);
                        restriction = Restriction.IN.create(t);
                    }
                    else
                    {
                        List<Term> inValues = new ArrayList<Term>(newRel.getInValues().size());
                        for (Term.Raw raw : newRel.getInValues())
                        {
                            Term t = raw.prepare(keyspace(), receiver);
                            t.collectMarkerSpecification(boundNames);
                            inValues.add(t);
                        }
                        restriction = Restriction.IN.create(inValues);
                    }
                    break;
                case GT:
                case GTE:
                case LT:
                case LTE:
                    {
                        if (restriction == null)
                            restriction = new Restriction.Slice(newRel.onToken);
                        else if (!restriction.isSlice())
                            throw new InvalidRequestException(String.format("%s cannot be restricted by both an equal and an inequal relation", def.name));
                        Term t = newRel.getValue().prepare(keyspace(), receiver);
                        t.collectMarkerSpecification(boundNames);
                        ((Restriction.Slice)restriction).setBound(def.name, newRel.operator(), t);
                    }
                    break;
                case CONTAINS_KEY:
                    if (!(receiver.type instanceof MapType))
                        throw new InvalidRequestException(String.format("Cannot use CONTAINS_KEY on non-map column %s", def.name));
                    // Fallthrough on purpose
                case CONTAINS:
                    {
                        if (!receiver.type.isCollection())
                            throw new InvalidRequestException(String.format("Cannot use %s relation on non collection column %s", newRel.operator(), def.name));

                        if (restriction == null)
                            restriction = new Restriction.Contains();
                        else if (!restriction.isContains())
                            throw new InvalidRequestException(String.format("Collection column %s can only be restricted by CONTAINS or CONTAINS KEY", def.name));
                        boolean isKey = newRel.operator() == Relation.Type.CONTAINS_KEY;
                        receiver = makeCollectionReceiver(receiver, isKey);
                        Term t = newRel.getValue().prepare(keyspace(), receiver);
                        ((Restriction.Contains)restriction).add(t, isKey);
                    }
            }
            return restriction;
        }

        private static ColumnSpecification makeCollectionReceiver(ColumnSpecification collection, boolean isKey)
        {
            assert collection.type.isCollection();
            switch (((CollectionType)collection.type).kind)
            {
                case LIST:
                    assert !isKey;
                    return Lists.valueSpecOf(collection);
                case SET:
                    assert !isKey;
                    return Sets.valueSpecOf(collection);
                case MAP:
                    return isKey ? Maps.keySpecOf(collection) : Maps.valueSpecOf(collection);
            }
            throw new AssertionError();
        }

        @Override
        public String toString()
        {
            return Objects.toStringHelper(this)
                          .add("name", cfName)
                          .add("selectClause", selectClause)
                          .add("whereClause", whereClause)
                          .add("isDistinct", parameters.isDistinct)
                          .add("isCount", parameters.isCount)
                          .toString();
        }
    }

    public static class Parameters
    {
        private final Map<ColumnIdentifier, Boolean> orderings;
        private final boolean isDistinct;
        private final boolean isCount;
        private final ColumnIdentifier countAlias;
        private final boolean allowFiltering;

        public Parameters(Map<ColumnIdentifier, Boolean> orderings,
                          boolean isDistinct,
                          boolean isCount,
                          ColumnIdentifier countAlias,
                          boolean allowFiltering)
        {
            this.orderings = orderings;
            this.isDistinct = isDistinct;
            this.isCount = isCount;
            this.countAlias = countAlias;
            this.allowFiltering = allowFiltering;
        }
    }

    /**
     * Used in orderResults(...) method when single 'ORDER BY' condition where given
     */
    private static class SingleColumnComparator implements Comparator<List<ByteBuffer>>
    {
        private final int index;
        private final Comparator<ByteBuffer> comparator;

        public SingleColumnComparator(int columnIndex, Comparator<ByteBuffer> orderer)
        {
            index = columnIndex;
            comparator = orderer;
        }

        public int compare(List<ByteBuffer> a, List<ByteBuffer> b)
        {
            return comparator.compare(a.get(index), b.get(index));
        }
    }

    /**
     * Used in orderResults(...) method when multiple 'ORDER BY' conditions where given
     */
    private static class CompositeComparator implements Comparator<List<ByteBuffer>>
    {
        private final List<Comparator<ByteBuffer>> orderTypes;
        private final List<Integer> positions;

        private CompositeComparator(List<Comparator<ByteBuffer>> orderTypes, List<Integer> positions)
        {
            this.orderTypes = orderTypes;
            this.positions = positions;
        }

        public int compare(List<ByteBuffer> a, List<ByteBuffer> b)
        {
            for (int i = 0; i < positions.size(); i++)
            {
                Comparator<ByteBuffer> type = orderTypes.get(i);
                int columnPos = positions.get(i);

                ByteBuffer aValue = a.get(columnPos);
                ByteBuffer bValue = b.get(columnPos);

                int comparison = type.compare(aValue, bValue);

                if (comparison != 0)
                    return comparison;
            }

            return 0;
        }
    }
}
