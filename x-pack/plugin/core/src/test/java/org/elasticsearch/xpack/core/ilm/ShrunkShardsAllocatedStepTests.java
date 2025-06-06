/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.ilm;

import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeUtils;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.NodeRoles;
import org.elasticsearch.xpack.core.ilm.ClusterStateWaitStep.Result;
import org.elasticsearch.xpack.core.ilm.Step.StepKey;

import static org.elasticsearch.xpack.core.ilm.ShrinkIndexNameSupplier.SHRUNKEN_INDEX_PREFIX;

public class ShrunkShardsAllocatedStepTests extends AbstractStepTestCase<ShrunkShardsAllocatedStep> {

    @Override
    public ShrunkShardsAllocatedStep createRandomInstance() {
        StepKey stepKey = randomStepKey();
        StepKey nextStepKey = randomStepKey();
        return new ShrunkShardsAllocatedStep(stepKey, nextStepKey);
    }

    @Override
    public ShrunkShardsAllocatedStep mutateInstance(ShrunkShardsAllocatedStep instance) {
        StepKey key = instance.getKey();
        StepKey nextKey = instance.getNextStepKey();

        switch (between(0, 1)) {
            case 0 -> key = new StepKey(key.phase(), key.action(), key.name() + randomAlphaOfLength(5));
            case 1 -> nextKey = new StepKey(nextKey.phase(), nextKey.action(), nextKey.name() + randomAlphaOfLength(5));
            default -> throw new AssertionError("Illegal randomisation branch");
        }

        return new ShrunkShardsAllocatedStep(key, nextKey);
    }

    @Override
    public ShrunkShardsAllocatedStep copyInstance(ShrunkShardsAllocatedStep instance) {
        return new ShrunkShardsAllocatedStep(instance.getKey(), instance.getNextStepKey());
    }

    public void testConditionMet() {
        ShrunkShardsAllocatedStep step = createRandomInstance();
        int shrinkNumberOfShards = randomIntBetween(1, 5);
        int originalNumberOfShards = randomIntBetween(1, 5);
        String originalIndexName = randomAlphaOfLength(5);
        IndexMetadata originalIndexMetadata = IndexMetadata.builder(originalIndexName)
            .settings(settings(IndexVersion.current()))
            .numberOfShards(originalNumberOfShards)
            .numberOfReplicas(0)
            .build();
        IndexMetadata shrunkIndexMetadata = IndexMetadata.builder(SHRUNKEN_INDEX_PREFIX + originalIndexName)
            .settings(settings(IndexVersion.current()))
            .numberOfShards(shrinkNumberOfShards)
            .numberOfReplicas(0)
            .build();
        ProjectMetadata project = ProjectMetadata.builder(randomProjectIdOrDefault())
            .put(IndexMetadata.builder(originalIndexMetadata))
            .put(IndexMetadata.builder(shrunkIndexMetadata))
            .build();
        Index shrinkIndex = shrunkIndexMetadata.getIndex();

        String nodeId = randomAlphaOfLength(10);
        DiscoveryNode masterNode = DiscoveryNodeUtils.builder(nodeId)
            .applySettings(NodeRoles.masterNode(settings(IndexVersion.current()).build()))
            .address(new TransportAddress(TransportAddress.META_ADDRESS, 9300))
            .build();

        IndexRoutingTable.Builder builder = IndexRoutingTable.builder(shrinkIndex);
        for (int i = 0; i < shrinkNumberOfShards; i++) {
            builder.addShard(TestShardRouting.newShardRouting(new ShardId(shrinkIndex, i), nodeId, true, ShardRoutingState.STARTED));
        }
        ProjectState state = ClusterState.builder(ClusterName.DEFAULT)
            .putProjectMetadata(project)
            .nodes(DiscoveryNodes.builder().localNodeId(nodeId).masterNodeId(nodeId).add(masterNode).build())
            .putRoutingTable(project.id(), RoutingTable.builder().add(builder.build()).build())
            .build()
            .projectState(project.id());

        Result result = step.isConditionMet(originalIndexMetadata.getIndex(), state);
        assertTrue(result.complete());
        assertNull(result.informationContext());
    }

    public void testConditionNotMetBecauseOfActive() {
        ShrunkShardsAllocatedStep step = createRandomInstance();
        int shrinkNumberOfShards = randomIntBetween(1, 5);
        int originalNumberOfShards = randomIntBetween(1, 5);
        String originalIndexName = randomAlphaOfLength(5);
        IndexMetadata originalIndexMetadata = IndexMetadata.builder(originalIndexName)
            .settings(settings(IndexVersion.current()))
            .numberOfShards(originalNumberOfShards)
            .numberOfReplicas(0)
            .build();
        IndexMetadata shrunkIndexMetadata = IndexMetadata.builder(SHRUNKEN_INDEX_PREFIX + originalIndexName)
            .settings(settings(IndexVersion.current()))
            .numberOfShards(shrinkNumberOfShards)
            .numberOfReplicas(0)
            .build();
        ProjectMetadata project = ProjectMetadata.builder(randomProjectIdOrDefault())
            .put(IndexMetadata.builder(originalIndexMetadata))
            .put(IndexMetadata.builder(shrunkIndexMetadata))
            .build();
        Index shrinkIndex = shrunkIndexMetadata.getIndex();

        String nodeId = randomAlphaOfLength(10);
        DiscoveryNode masterNode = DiscoveryNodeUtils.builder(nodeId)
            .applySettings(NodeRoles.masterNode(settings(IndexVersion.current()).build()))
            .address(new TransportAddress(TransportAddress.META_ADDRESS, 9300))
            .build();

        IndexRoutingTable.Builder builder = IndexRoutingTable.builder(shrinkIndex);
        for (int i = 0; i < shrinkNumberOfShards; i++) {
            builder.addShard(TestShardRouting.newShardRouting(new ShardId(shrinkIndex, i), nodeId, true, ShardRoutingState.INITIALIZING));
        }
        ProjectState state = ClusterState.builder(ClusterName.DEFAULT)
            .putProjectMetadata(project)
            .nodes(DiscoveryNodes.builder().localNodeId(nodeId).masterNodeId(nodeId).add(masterNode).build())
            .putRoutingTable(project.id(), RoutingTable.builder().add(builder.build()).build())
            .build()
            .projectState(project.id());

        Result result = step.isConditionMet(originalIndexMetadata.getIndex(), state);
        assertFalse(result.complete());
        assertEquals(new ShrunkShardsAllocatedStep.Info(true, shrinkNumberOfShards, false), result.informationContext());
    }

    public void testConditionNotMetBecauseOfShrunkIndexDoesntExistYet() {
        ShrunkShardsAllocatedStep step = createRandomInstance();
        int originalNumberOfShards = randomIntBetween(1, 5);
        String originalIndexName = randomAlphaOfLength(5);
        IndexMetadata originalIndexMetadata = IndexMetadata.builder(originalIndexName)
            .settings(settings(IndexVersion.current()))
            .numberOfShards(originalNumberOfShards)
            .numberOfReplicas(0)
            .build();
        ProjectMetadata project = ProjectMetadata.builder(randomProjectIdOrDefault())
            .put(IndexMetadata.builder(originalIndexMetadata))
            .build();

        String nodeId = randomAlphaOfLength(10);
        DiscoveryNode masterNode = DiscoveryNodeUtils.builder(nodeId)
            .applySettings(NodeRoles.masterNode(settings(IndexVersion.current()).build()))
            .address(new TransportAddress(TransportAddress.META_ADDRESS, 9300))
            .build();
        ProjectState state = ClusterState.builder(ClusterName.DEFAULT)
            .putProjectMetadata(project)
            .nodes(DiscoveryNodes.builder().localNodeId(nodeId).masterNodeId(nodeId).add(masterNode).build())
            .build()
            .projectState(project.id());

        Result result = step.isConditionMet(originalIndexMetadata.getIndex(), state);
        assertFalse(result.complete());
        assertEquals(new ShrunkShardsAllocatedStep.Info(false, -1, false), result.informationContext());
    }
}
