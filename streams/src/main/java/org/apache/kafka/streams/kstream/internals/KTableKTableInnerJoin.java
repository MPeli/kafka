/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.streams.processor.api.ContextualProcessor;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.kafka.streams.processor.internals.metrics.TaskMetrics.droppedRecordsSensor;
import static org.apache.kafka.streams.state.ValueAndTimestamp.getValueOrNull;

class KTableKTableInnerJoin<K, V1, V2, VOut> extends KTableKTableAbstractJoin<K, V1, V2, VOut> {
    private static final Logger LOG = LoggerFactory.getLogger(KTableKTableInnerJoin.class);

    private final KeyValueMapper<K, V1, K> keyValueMapper = (key, value) -> key;

    KTableKTableInnerJoin(final KTableImpl<K, ?, V1> table1,
                          final KTableImpl<K, ?, V2> table2,
                          final ValueJoiner<? super V1, ? super V2, ? extends VOut> joiner) {
        super(table1, table2, joiner);
    }

    @Override
    public Processor<K, Change<V1>, K, Change<VOut>> get() {
        return new KTableKTableJoinProcessor(valueGetter1, valueGetter2);
    }

    @Override
    public KTableValueGetterSupplier<K, VOut> view() {
        return new KTableKTableInnerJoinValueGetterSupplier(valueGetterSupplier1, valueGetterSupplier2);
    }

    private class KTableKTableInnerJoinValueGetterSupplier extends KTableKTableAbstractJoinValueGetterSupplier<K, VOut, V1, V2> {

        KTableKTableInnerJoinValueGetterSupplier(final KTableValueGetterSupplier<K, V1> valueGetterSupplier1,
                                                 final KTableValueGetterSupplier<K, V2> valueGetterSupplier2) {
            super(valueGetterSupplier1, valueGetterSupplier2);
        }

        public KTableValueGetter<K, VOut> get() {
            return new KTableKTableInnerJoinValueGetter(valueGetterSupplier1.get(), valueGetterSupplier2.get());
        }
    }

    private class KTableKTableJoinProcessor extends ContextualProcessor<K, Change<V1>, K, Change<VOut>> {

        private final KTableValueGetter<K, V1> thisValueGetter;
        private final KTableValueGetter<K, V2> otherValueGetter;
        private Sensor droppedRecordsSensor;

        KTableKTableJoinProcessor(final KTableValueGetter<K, V1> thisValueGetter,
                                  final KTableValueGetter<K, V2> otherValueGetter) {
            this.thisValueGetter = thisValueGetter;
            this.otherValueGetter = otherValueGetter;
        }

        @Override
        public void init(final ProcessorContext<K, Change<VOut>> context) {
            super.init(context);
            droppedRecordsSensor = droppedRecordsSensor(
                Thread.currentThread().getName(),
                context.taskId().toString(),
                (StreamsMetricsImpl) context.metrics()
            );
            thisValueGetter.init(context);
            otherValueGetter.init(context);
        }

        @Override
        public void process(final Record<K, Change<V1>> record) {
            // we do join iff keys are equal, thus, if key is null we cannot join and just ignore the record
            if (record.key() == null) {
                if (context().recordMetadata().isPresent()) {
                    final RecordMetadata recordMetadata = context().recordMetadata().get();
                    LOG.warn(
                        "Skipping record due to null key. "
                            + "topic=[{}] partition=[{}] offset=[{}]",
                        recordMetadata.topic(), recordMetadata.partition(), recordMetadata.offset()
                    );
                } else {
                    LOG.warn(
                        "Skipping record due to null key. Topic, partition, and offset not known."
                    );
                }
                droppedRecordsSensor.record();
                return;
            }

            // drop out-of-order records from versioned tables (cf. KIP-914)
            if (thisValueGetter.isVersioned()) {
                final ValueAndTimestamp<V1> valueAndTimestampLeft = thisValueGetter.get(record.key());
                if (valueAndTimestampLeft != null && valueAndTimestampLeft.timestamp() > record.timestamp()) {
                    LOG.info("Skipping out-of-order record from versioned table while performing table-table join.");
                    droppedRecordsSensor.record();
                    return;
                }
            }

            VOut newValue = null;
            final long resultTimestamp;
            VOut oldValue = null;

            final ValueAndTimestamp<V2> valueAndTimestampRight = otherValueGetter.get(record.key());
            final V2 valueRight = getValueOrNull(valueAndTimestampRight);
            if (valueRight == null) {
                return;
            }

            resultTimestamp = Math.max(record.timestamp(), valueAndTimestampRight.timestamp());

            if (record.value().newValue != null) {
                newValue = joiner.apply(record.value().newValue, valueRight);
            }

            if (sendOldValues && record.value().oldValue != null) {
                oldValue = joiner.apply(record.value().oldValue, valueRight);
            }

            context().forward(record.withValue(new Change<>(newValue, oldValue, record.value().isLatest)).withTimestamp(resultTimestamp));
        }

        @Override
        public void close() {
            thisValueGetter.close();
            otherValueGetter.close();
        }
    }

    private class KTableKTableInnerJoinValueGetter implements KTableValueGetter<K, VOut> {

        private final KTableValueGetter<K, V1> valueGetter1;
        private final KTableValueGetter<K, V2> valueGetter2;

        KTableKTableInnerJoinValueGetter(final KTableValueGetter<K, V1> valueGetter1,
                                         final KTableValueGetter<K, V2> valueGetter2) {
            this.valueGetter1 = valueGetter1;
            this.valueGetter2 = valueGetter2;
        }

        @Override
        public void init(final ProcessorContext<?, ?> context) {
            valueGetter1.init(context);
            valueGetter2.init(context);
        }

        @Override
        public ValueAndTimestamp<VOut> get(final K key) {
            final ValueAndTimestamp<V1> valueAndTimestamp1 = valueGetter1.get(key);
            final V1 value1 = getValueOrNull(valueAndTimestamp1);

            if (value1 != null) {
                final ValueAndTimestamp<V2> valueAndTimestamp2
                    = valueGetter2.get(keyValueMapper.apply(key, value1));
                final V2 value2 = getValueOrNull(valueAndTimestamp2);

                if (value2 != null) {
                    return ValueAndTimestamp.make(
                        joiner.apply(value1, value2),
                        Math.max(valueAndTimestamp1.timestamp(), valueAndTimestamp2.timestamp()));
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }

        @Override
        public boolean isVersioned() {
            // even though we can derive a proper versioned result (assuming both parent value
            // getters are versioned), we choose not to since the output of a join of two
            // versioned tables today is not considered versioned (cf KIP-914)
            return false;
        }

        @Override
        public void close() {
            valueGetter1.close();
            valueGetter2.close();
        }
    }
}
