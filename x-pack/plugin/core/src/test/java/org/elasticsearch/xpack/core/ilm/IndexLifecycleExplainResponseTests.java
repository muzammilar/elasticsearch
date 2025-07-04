/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.ilm;

import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.AbstractXContentSerializingTestCase;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

public class IndexLifecycleExplainResponseTests extends AbstractXContentSerializingTestCase<IndexLifecycleExplainResponse> {

    static IndexLifecycleExplainResponse randomIndexExplainResponse() {
        final IndexLifecycleExplainResponse indexLifecycleExplainResponse;
        if (frequently()) {
            indexLifecycleExplainResponse = randomManagedIndexExplainResponse();
        } else {
            indexLifecycleExplainResponse = randomUnmanagedIndexExplainResponse();
        }
        long now = System.currentTimeMillis();
        // So that now is the same for the duration of the test. See #84352
        indexLifecycleExplainResponse.nowSupplier = () -> now;
        return indexLifecycleExplainResponse;
    }

    private static IndexLifecycleExplainResponse randomUnmanagedIndexExplainResponse() {
        return IndexLifecycleExplainResponse.newUnmanagedIndexResponse(randomAlphaOfLength(10));
    }

    private static IndexLifecycleExplainResponse randomManagedIndexExplainResponse() {
        boolean stepNull = randomBoolean();
        return IndexLifecycleExplainResponse.newManagedIndexResponse(
            randomAlphaOfLength(10),
            randomBoolean() ? null : randomLongBetween(0, System.currentTimeMillis()),
            randomAlphaOfLength(10),
            randomBoolean() ? null : randomLongBetween(0, System.currentTimeMillis()),
            stepNull ? null : randomAlphaOfLength(10),
            stepNull ? null : randomAlphaOfLength(10),
            stepNull ? null : randomAlphaOfLength(10),
            randomBoolean() ? null : randomAlphaOfLength(10),
            stepNull ? null : randomBoolean(),
            stepNull ? null : randomInt(10),
            stepNull ? null : randomNonNegativeLong(),
            stepNull ? null : randomNonNegativeLong(),
            stepNull ? null : randomNonNegativeLong(),
            stepNull ? null : randomAlphaOfLength(10),
            stepNull ? null : randomAlphaOfLength(10),
            stepNull ? null : randomAlphaOfLength(10),
            randomBoolean() ? null : new BytesArray(new RandomStepInfo(() -> randomAlphaOfLength(10)).toString()),
            randomBoolean() ? null : new BytesArray(new RandomStepInfo(() -> randomAlphaOfLength(10)).toString()),
            randomBoolean() ? null : PhaseExecutionInfoTests.randomPhaseExecutionInfo(""),
            randomBoolean()
        );
    }

    public void testInvalidStepDetails() {
        final int numNull = randomIntBetween(1, 3);
        IllegalArgumentException exception = expectThrows(
            IllegalArgumentException.class,
            () -> IndexLifecycleExplainResponse.newManagedIndexResponse(
                randomAlphaOfLength(10),
                randomNonNegativeLong(),
                randomAlphaOfLength(10),
                randomBoolean() ? null : randomNonNegativeLong(),
                (numNull == 1) ? null : randomAlphaOfLength(10),
                (numNull == 2) ? null : randomAlphaOfLength(10),
                (numNull == 3) ? null : randomAlphaOfLength(10),
                randomBoolean() ? null : randomAlphaOfLength(10),
                randomBoolean() ? null : randomBoolean(),
                randomBoolean() ? null : randomInt(10),
                randomBoolean() ? null : randomNonNegativeLong(),
                randomBoolean() ? null : randomNonNegativeLong(),
                randomBoolean() ? null : randomNonNegativeLong(),
                randomBoolean() ? null : randomAlphaOfLength(10),
                randomBoolean() ? null : randomAlphaOfLength(10),
                randomBoolean() ? null : randomAlphaOfLength(10),
                randomBoolean() ? null : new BytesArray(new RandomStepInfo(() -> randomAlphaOfLength(10)).toString()),
                randomBoolean() ? null : new BytesArray(new RandomStepInfo(() -> randomAlphaOfLength(10)).toString()),
                randomBoolean() ? null : PhaseExecutionInfoTests.randomPhaseExecutionInfo(""),
                randomBoolean()
            )
        );
        assertThat(exception.getMessage(), startsWith("managed index response must have complete step details"));
        assertThat(exception.getMessage(), containsString("=null"));
    }

    public void testIndexAges() throws IOException {
        IndexLifecycleExplainResponse unmanagedExplainResponse = randomUnmanagedIndexExplainResponse();
        assertThat(unmanagedExplainResponse.getLifecycleDate(), is(nullValue()));
        assertThat(unmanagedExplainResponse.getAge(System::currentTimeMillis), is(TimeValue.MINUS_ONE));

        assertThat(unmanagedExplainResponse.getIndexCreationDate(), is(nullValue()));
        assertThat(unmanagedExplainResponse.getTimeSinceIndexCreation(System::currentTimeMillis), is(nullValue()));

        assertAgeInMillisXContentAbsentForUnmanagedResponse(unmanagedExplainResponse);

        IndexLifecycleExplainResponse managedExplainResponse = IndexLifecycleExplainResponse.newManagedIndexResponse(
            "indexName",
            12345L,
            "policy",
            5678L,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false
        );
        assertThat(managedExplainResponse.getLifecycleDate(), is(notNullValue()));
        Long now = 1_000_000L;
        assertThat(managedExplainResponse.getAge(() -> now), is(notNullValue()));
        assertThat(
            managedExplainResponse.getAge(() -> now),
            is(equalTo(TimeValue.timeValueMillis(now - managedExplainResponse.getLifecycleDate())))
        );
        assertThat(managedExplainResponse.getAge(() -> 0L), is(equalTo(TimeValue.ZERO)));
        assertThat(managedExplainResponse.getIndexCreationDate(), is(notNullValue()));
        assertThat(managedExplainResponse.getTimeSinceIndexCreation(() -> now), is(notNullValue()));
        assertThat(
            managedExplainResponse.getTimeSinceIndexCreation(() -> now),
            is(equalTo(TimeValue.timeValueMillis(now - managedExplainResponse.getIndexCreationDate())))
        );
        assertThat(managedExplainResponse.getTimeSinceIndexCreation(() -> 0L), is(equalTo(TimeValue.ZERO)));

        long expectedAgeInMillisForThisCase = Math.max(0L, now - managedExplainResponse.getLifecycleDate());
        assertAgeInMillisXContent(managedExplainResponse, expectedAgeInMillisForThisCase, now);
    }

    protected void assertAgeInMillisXContent(
        final IndexLifecycleExplainResponse managedExplainResponse,
        final long expectedAgeInMillis,
        final long now
    ) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        managedExplainResponse.nowSupplier = () -> now;
        try (builder) {
            managedExplainResponse.toXContent(builder, ToXContentObject.EMPTY_PARAMS);
        }
        final String json = Strings.toString(builder);

        try (XContentParser parser = createParser(builder.contentType().xContent(), json)) {
            Map<String, Object> parsedMap = parser.map();

            assertThat(parsedMap, hasKey("age_in_millis"));
            final long actualParsedAgeInMillis = ((Number) parsedMap.get("age_in_millis")).longValue();
            assertThat(actualParsedAgeInMillis, equalTo((Number) expectedAgeInMillis));
        }
    }

    protected void assertAgeInMillisXContentAbsentForUnmanagedResponse(final IndexLifecycleExplainResponse unmanagedExplainResponse)
        throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        try (builder) {
            unmanagedExplainResponse.toXContent(builder, ToXContentObject.EMPTY_PARAMS);
        }
        final String json = Strings.toString(builder);

        try (XContentParser parser = createParser(builder.contentType().xContent(), json)) {
            Map<String, Object> parsedMap = parser.map();

            assertThat(parsedMap, not(hasKey("age_in_millis")));
        }

    }

    @Override
    protected IndexLifecycleExplainResponse createTestInstance() {
        return randomIndexExplainResponse();
    }

    @Override
    protected Reader<IndexLifecycleExplainResponse> instanceReader() {
        return IndexLifecycleExplainResponse::new;
    }

    @Override
    protected IndexLifecycleExplainResponse doParseInstance(XContentParser parser) throws IOException {
        return IndexLifecycleExplainResponse.PARSER.apply(parser, null);
    }

    @Override
    protected boolean assertToXContentEquivalence() {
        return false;
    }

    @Override
    protected IndexLifecycleExplainResponse mutateInstance(IndexLifecycleExplainResponse instance) {
        String index = instance.getIndex();
        Long indexCreationDate = instance.getIndexCreationDate();
        String policy = instance.getPolicyName();
        String phase = instance.getPhase();
        String action = instance.getAction();
        String step = instance.getStep();
        String failedStep = instance.getFailedStep();
        Boolean isAutoRetryableError = instance.isAutoRetryableError();
        Integer failedStepRetryCount = instance.getFailedStepRetryCount();
        Long policyTime = instance.getLifecycleDate();
        Long phaseTime = instance.getPhaseTime();
        Long actionTime = instance.getActionTime();
        Long stepTime = instance.getStepTime();
        String repositoryName = instance.getRepositoryName();
        String snapshotName = instance.getSnapshotName();
        String shrinkIndexName = instance.getShrinkIndexName();
        boolean managed = instance.managedByILM();
        BytesReference stepInfo = instance.getStepInfo();
        BytesReference previousStepInfo = instance.getPreviousStepInfo();
        PhaseExecutionInfo phaseExecutionInfo = instance.getPhaseExecutionInfo();
        boolean skip = instance.getSkip();

        if (managed) {
            switch (between(0, 16)) {
                case 0 -> index += randomAlphaOfLengthBetween(1, 5);
                case 1 -> policy += randomAlphaOfLengthBetween(1, 5);
                case 2 -> {
                    phase = randomAlphaOfLengthBetween(1, 5);
                    action = randomAlphaOfLengthBetween(1, 5);
                    step = randomAlphaOfLengthBetween(1, 5);
                }
                case 3 -> phaseTime = randomValueOtherThan(phaseTime, () -> randomLongBetween(0, 100000));
                case 4 -> actionTime = randomValueOtherThan(actionTime, () -> randomLongBetween(0, 100000));
                case 5 -> stepTime = randomValueOtherThan(stepTime, () -> randomLongBetween(0, 100000));
                case 6 -> {
                    if (Strings.hasLength(failedStep) == false) {
                        failedStep = randomAlphaOfLength(10);
                    } else if (randomBoolean()) {
                        failedStep += randomAlphaOfLengthBetween(1, 5);
                    } else {
                        failedStep = null;
                    }
                }
                case 7 -> policyTime = randomValueOtherThan(policyTime, () -> randomLongBetween(0, 100000));
                case 8 -> {
                    if (Strings.hasLength(stepInfo) == false) {
                        stepInfo = new BytesArray(randomByteArrayOfLength(100));
                    } else if (randomBoolean()) {
                        stepInfo = randomValueOtherThan(
                            stepInfo,
                            () -> new BytesArray(new RandomStepInfo(() -> randomAlphaOfLength(10)).toString())
                        );
                    } else {
                        stepInfo = null;
                    }
                }
                case 9 -> {
                    if (Strings.hasLength(previousStepInfo) == false) {
                        previousStepInfo = new BytesArray(randomByteArrayOfLength(100));
                    } else if (randomBoolean()) {
                        previousStepInfo = randomValueOtherThan(
                            previousStepInfo,
                            () -> new BytesArray(new RandomStepInfo(() -> randomAlphaOfLength(10)).toString())
                        );
                    } else {
                        previousStepInfo = null;
                    }
                }
                case 10 -> phaseExecutionInfo = randomValueOtherThan(
                    phaseExecutionInfo,
                    () -> PhaseExecutionInfoTests.randomPhaseExecutionInfo("")
                );
                case 11 -> {
                    return IndexLifecycleExplainResponse.newUnmanagedIndexResponse(index);
                }
                case 12 -> {
                    isAutoRetryableError = true;
                    failedStepRetryCount = randomValueOtherThan(failedStepRetryCount, () -> randomInt(10));
                }
                case 13 -> repositoryName = randomValueOtherThan(repositoryName, () -> randomAlphaOfLengthBetween(5, 10));
                case 14 -> snapshotName = randomValueOtherThan(snapshotName, () -> randomAlphaOfLengthBetween(5, 10));
                case 15 -> shrinkIndexName = randomValueOtherThan(shrinkIndexName, () -> randomAlphaOfLengthBetween(5, 10));
                case 16 -> skip = skip == false;
                default -> throw new AssertionError("Illegal randomisation branch");
            }

            return IndexLifecycleExplainResponse.newManagedIndexResponse(
                index,
                indexCreationDate,
                policy,
                policyTime,
                phase,
                action,
                step,
                failedStep,
                isAutoRetryableError,
                failedStepRetryCount,
                phaseTime,
                actionTime,
                stepTime,
                repositoryName,
                snapshotName,
                shrinkIndexName,
                stepInfo,
                previousStepInfo,
                phaseExecutionInfo,
                skip
            );
        } else {
            return switch (between(0, 1)) {
                case 0 -> IndexLifecycleExplainResponse.newUnmanagedIndexResponse(index + randomAlphaOfLengthBetween(1, 5));
                case 1 -> randomManagedIndexExplainResponse();
                default -> throw new AssertionError("Illegal randomisation branch");
            };
        }
    }

    protected NamedWriteableRegistry getNamedWriteableRegistry() {
        return new NamedWriteableRegistry(
            List.of(new NamedWriteableRegistry.Entry(LifecycleAction.class, MockAction.NAME, MockAction::new))
        );
    }

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        return new NamedXContentRegistry(
            CollectionUtils.appendToCopy(
                ClusterModule.getNamedXWriteables(),
                new NamedXContentRegistry.Entry(LifecycleAction.class, new ParseField(MockAction.NAME), MockAction::parse)
            )
        );
    }

    private static class RandomStepInfo implements ToXContentObject {

        private final String key;
        private final String value;

        RandomStepInfo(Supplier<String> randomStringSupplier) {
            this.key = randomStringSupplier.get();
            this.value = randomStringSupplier.get();
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(key, value);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            RandomStepInfo other = (RandomStepInfo) obj;
            return Objects.equals(key, other.key) && Objects.equals(value, other.value);
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }
    }

}
