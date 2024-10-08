/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.aggregations.bucket.histogram;

import org.apache.lucene.search.ScoreMode;
import org.elasticsearch.index.fielddata.FieldData;
import org.elasticsearch.index.fielddata.NumericDoubleValues;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.search.aggregations.AggregationExecutionContext;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.CardinalityUpperBound;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;

import java.io.IOException;
import java.util.Map;

/**
 * An aggregator for numeric values. For a given {@code interval},
 * {@code offset} and {@code value}, it returns the highest number that can be
 * written as {@code interval * x + offset} and yet is less than or equal to
 * {@code value}.
 */
public class NumericHistogramAggregator extends AbstractHistogramAggregator {
    private final ValuesSource.Numeric valuesSource;

    public NumericHistogramAggregator(
        String name,
        AggregatorFactories factories,
        double interval,
        double offset,
        BucketOrder order,
        boolean keyed,
        long minDocCount,
        DoubleBounds extendedBounds,
        DoubleBounds hardBounds,
        ValuesSourceConfig valuesSourceConfig,
        AggregationContext context,
        Aggregator parent,
        CardinalityUpperBound cardinalityUpperBound,
        Map<String, Object> metadata
    ) throws IOException {
        super(
            name,
            factories,
            interval,
            offset,
            order,
            keyed,
            minDocCount,
            extendedBounds,
            hardBounds,
            valuesSourceConfig.format(),
            context,
            parent,
            cardinalityUpperBound,
            metadata
        );
        // TODO: Stop using null here
        this.valuesSource = valuesSourceConfig.hasValues() ? (ValuesSource.Numeric) valuesSourceConfig.getValuesSource() : null;
    }

    @Override
    public ScoreMode scoreMode() {
        if (valuesSource != null && valuesSource.needsScores()) {
            return ScoreMode.COMPLETE;
        }
        return super.scoreMode();
    }

    @Override
    public LeafBucketCollector getLeafCollector(AggregationExecutionContext aggCtx, final LeafBucketCollector sub) throws IOException {
        if (valuesSource == null) {
            return LeafBucketCollector.NO_OP_COLLECTOR;
        }

        final SortedNumericDoubleValues values = valuesSource.doubleValues(aggCtx.getLeafReaderContext());
        final NumericDoubleValues singleton = FieldData.unwrapSingleton(values);
        return singleton != null ? getLeafCollector(singleton, sub) : getLeafCollector(values, sub);
    }

    private LeafBucketCollector getLeafCollector(SortedNumericDoubleValues values, LeafBucketCollector sub) {
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long owningBucketOrd) throws IOException {
                if (values.advanceExact(doc)) {
                    double previousKey = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < values.docValueCount(); ++i) {
                        final double key = Math.floor((values.nextValue() - offset) / interval);
                        assert key >= previousKey;
                        if (key == previousKey) {
                            continue;
                        }
                        addKey(key, doc, owningBucketOrd, sub);
                        previousKey = key;
                    }
                }
            }
        };
    }

    private LeafBucketCollector getLeafCollector(NumericDoubleValues values, LeafBucketCollector sub) {
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long owningBucketOrd) throws IOException {
                if (values.advanceExact(doc)) {
                    addKey(Math.floor((values.doubleValue() - offset) / interval), doc, owningBucketOrd, sub);
                }
            }
        };
    }

    private void addKey(double key, int doc, long owningBucketOrd, LeafBucketCollector sub) throws IOException {
        if (hardBounds == null || hardBounds.contain(key * interval)) {
            long bucketOrd = bucketOrds.add(owningBucketOrd, Double.doubleToLongBits(key));
            if (bucketOrd < 0) { // already seen
                bucketOrd = -1 - bucketOrd;
                collectExistingBucket(sub, doc, bucketOrd);
            } else {
                collectBucket(sub, doc, bucketOrd);
            }
        }
    }
}
