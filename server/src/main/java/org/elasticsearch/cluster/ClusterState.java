/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster;

import org.elasticsearch.Version;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.coordination.ClusterStatePublisher;
import org.elasticsearch.cluster.coordination.CoordinationMetadata;
import org.elasticsearch.cluster.coordination.CoordinationMetadata.VotingConfigExclusion;
import org.elasticsearch.cluster.coordination.CoordinationMetadata.VotingConfiguration;
import org.elasticsearch.cluster.coordination.NoMasterBlockService;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterApplierService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.VersionedNamedWriteable;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContent;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the state of the cluster, held in memory on all nodes in the cluster with updates coordinated by the elected master.
 * <p>
 * Conceptually immutable, but in practice it has a few components like {@link RoutingNodes} which are pure functions of the immutable state
 * but are expensive to compute so they are built on-demand if needed.
 * <p>
 * The {@link Metadata} portion is written to disk on each update so it persists across full-cluster restarts. The rest of this data is
 * maintained only in-memory and resets back to its initial state on a full-cluster restart, but it is held on all nodes so it persists
 * across master elections (and therefore is preserved in a rolling restart).
 * <p>
 * Updates are triggered by submitting tasks to the {@link MasterService} on the elected master, typically using a {@link
 * TransportMasterNodeAction} to route a request to the master on which the task is submitted with {@link
 * ClusterService#submitStateUpdateTask}. Submitted tasks have an associated {@link ClusterStateTaskConfig} which defines a priority and a
 * timeout. Tasks are processed in priority order, so a flood of higher-priority tasks can starve lower-priority ones from running.
 * Therefore, avoid priorities other than {@link Priority#NORMAL} where possible. Tasks associated with client actions should typically have
 * a timeout, or otherwise be sensitive to client cancellations, to avoid surprises caused by the execution of stale tasks long after they
 * are submitted (since clients themselves tend to time out). In contrast, internal tasks can reasonably have an infinite timeout,
 * especially if a timeout would simply trigger a retry.
 * <p>
 * Tasks that share the same {@link ClusterStateTaskExecutor} instance are processed as a batch. Each batch of tasks yields a new {@link
 * ClusterState} which is published to the cluster by {@link ClusterStatePublisher#publish}. Publication usually works by sending a diff,
 * computed via the {@link Diffable} interface, rather than the full state, although it will fall back to sending the full state if the
 * receiving node is new or it has missed out on an intermediate state for some reason. States and diffs are published using the transport
 * protocol, i.e. the {@link Writeable} interface and friends.
 * <p>
 * When committed, the new state is <i>applied</i> which exposes it to the node via {@link ClusterStateApplier} and {@link
 * ClusterStateListener} callbacks registered with the {@link ClusterApplierService}. The new state is also made available via {@link
 * ClusterService#state()}. The appliers are notified (in no particular order) before {@link ClusterService#state()} is updated, and the
 * listeners are notified (in no particular order) afterwards. Cluster state updates run in sequence, one-by-one, so they can be a
 * performance bottleneck. See the JavaDocs on the linked classes and methods for more details.
 * <p>
 * Cluster state updates can be used to trigger various actions via a {@link ClusterStateListener} rather than using a timer.
 * <p>
 * Implements {@link ToXContentFragment} to be exposed in REST APIs (e.g. {@code GET _cluster/state} and {@code POST _cluster/reroute}) and
 * to be indexed by monitoring, mostly just for diagnostics purposes. The {@link XContent} representation does not need to be 100% faithful
 * since we never reconstruct a cluster state from its XContent representation, but the more faithful it is the more useful it is for
 * diagnostics. Note that the {@link XContent} representation of the {@link Metadata} portion does have to be faithful (in {@link
 * Metadata.XContentContext#GATEWAY} context) since this is how it persists across full cluster restarts.
 * <p>
 * Security-sensitive data such as passwords or private keys should not be stored in the cluster state, since the contents of the cluster
 * state are exposed in various APIs.
 */
public class ClusterState implements ToXContentFragment, Diffable<ClusterState> {

    public static final ClusterState EMPTY_STATE = builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)).build();

    public interface Custom extends NamedDiffable<Custom>, ToXContentFragment {

        /**
         * Returns <code>true</code> iff this {@link Custom} is private to the cluster and should never be send to a client.
         * The default is <code>false</code>;
         */
        default boolean isPrivate() {
            return false;
        }

        /**
         * Serialize this {@link Custom} for diagnostic purposes, exposed by the <pre>GET _cluster/state</pre> API etc. The XContent
         * representation does not need to be 100% faithful since we never reconstruct a cluster state from its XContent representation, but
         * the more faithful it is the more useful it is for diagnostics.
         */
        @Override
        XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException;
    }

    private static final NamedDiffableValueSerializer<Custom> CUSTOM_VALUE_SERIALIZER = new NamedDiffableValueSerializer<>(Custom.class);

    public static final String UNKNOWN_UUID = "_na_";

    public static final long UNKNOWN_VERSION = -1;

    /**
     * Monotonically increasing on (and therefore uniquely identifies) <i>committed</i> states. However sometimes a state is created/applied
     * without committing it, for instance to add a {@link NoMasterBlockService#getNoMasterBlock}.
     */
    private final long version;

    /**
     * Uniquely identifies this state, even if the state is not committed.
     */
    private final String stateUUID;

    /**
     * Describes the location (and state) of all shards, used for routing actions such as searches to the relevant shards.
     */
    private final RoutingTable routingTable;

    private final DiscoveryNodes nodes;

    private final Metadata metadata;

    private final ClusterBlocks blocks;

    private final ImmutableOpenMap<String, Custom> customs;

    private final ClusterName clusterName;

    private final boolean wasReadFromDiff;

    // built on demand
    private volatile RoutingNodes routingNodes;

    public ClusterState(long version, String stateUUID, ClusterState state) {
        this(
            state.clusterName,
            version,
            stateUUID,
            state.metadata(),
            state.routingTable(),
            state.nodes(),
            state.blocks(),
            state.customs(),
            false,
            state.routingNodes
        );
    }

    public ClusterState(
        ClusterName clusterName,
        long version,
        String stateUUID,
        Metadata metadata,
        RoutingTable routingTable,
        DiscoveryNodes nodes,
        ClusterBlocks blocks,
        ImmutableOpenMap<String, Custom> customs,
        boolean wasReadFromDiff,
        @Nullable RoutingNodes routingNodes
    ) {
        this.version = version;
        this.stateUUID = stateUUID;
        this.clusterName = clusterName;
        this.metadata = metadata;
        this.routingTable = routingTable;
        this.nodes = nodes;
        this.blocks = blocks;
        this.customs = customs;
        this.wasReadFromDiff = wasReadFromDiff;
        this.routingNodes = routingNodes;
        assert assertConsistentRoutingNodes(routingTable, nodes, routingNodes);
    }

    private static boolean assertConsistentRoutingNodes(
        RoutingTable routingTable,
        DiscoveryNodes nodes,
        @Nullable RoutingNodes routingNodes
    ) {
        if (routingNodes == null) {
            return true;
        }
        final RoutingNodes expected = RoutingNodes.immutable(routingTable, nodes);
        assert routingNodes.equals(expected)
            : "RoutingNodes [" + routingNodes + "] are not consistent with this cluster state [" + expected + "]";
        return true;
    }

    public long term() {
        return coordinationMetadata().term();
    }

    public long version() {
        return this.version;
    }

    public long getVersion() {
        return version();
    }

    /**
     * This stateUUID is automatically generated for for each version of cluster state. It is used to make sure that
     * we are applying diffs to the right previous state.
     */
    public String stateUUID() {
        return this.stateUUID;
    }

    public DiscoveryNodes nodes() {
        return this.nodes;
    }

    public DiscoveryNodes getNodes() {
        return nodes();
    }

    public Metadata metadata() {
        return this.metadata;
    }

    public Metadata getMetadata() {
        return metadata();
    }

    public CoordinationMetadata coordinationMetadata() {
        return metadata.coordinationMetadata();
    }

    public RoutingTable routingTable() {
        return routingTable;
    }

    public RoutingTable getRoutingTable() {
        return routingTable();
    }

    public ClusterBlocks blocks() {
        return this.blocks;
    }

    public ClusterBlocks getBlocks() {
        return blocks;
    }

    public ImmutableOpenMap<String, Custom> customs() {
        return this.customs;
    }

    public ImmutableOpenMap<String, Custom> getCustoms() {
        return this.customs;
    }

    @SuppressWarnings("unchecked")
    public <T extends Custom> T custom(String type) {
        return (T) customs.get(type);
    }

    @SuppressWarnings("unchecked")
    public <T extends Custom> T custom(String type, T defaultValue) {
        return (T) customs.getOrDefault(type, defaultValue);
    }

    public ClusterName getClusterName() {
        return this.clusterName;
    }

    public VotingConfiguration getLastAcceptedConfiguration() {
        return coordinationMetadata().getLastAcceptedConfiguration();
    }

    public VotingConfiguration getLastCommittedConfiguration() {
        return coordinationMetadata().getLastCommittedConfiguration();
    }

    public Set<VotingConfigExclusion> getVotingConfigExclusions() {
        return coordinationMetadata().getVotingConfigExclusions();
    }

    /**
     * Returns a built (on demand) routing nodes view of the routing table.
     */
    public RoutingNodes getRoutingNodes() {
        if (routingNodes != null) {
            return routingNodes;
        }
        routingNodes = RoutingNodes.immutable(routingTable, nodes);
        return routingNodes;
    }

    /**
     * Returns a fresh mutable copy of the routing nodes view.
     */
    public RoutingNodes mutableRoutingNodes() {
        final RoutingNodes nodes = this.routingNodes;
        // use the cheaper copy constructor if we already computed the routing nodes for this state.
        if (nodes != null) {
            return nodes.mutableCopy();
        }
        // we don't have any routing nodes for this state, likely because it's a temporary state in the reroute logic, don't compute an
        // immutable copy that will never be used and instead directly build a mutable copy
        return RoutingNodes.mutable(routingTable, this.nodes);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        final String TAB = "   ";
        sb.append("cluster uuid: ")
            .append(metadata.clusterUUID())
            .append(" [committed: ")
            .append(metadata.clusterUUIDCommitted())
            .append("]")
            .append("\n");
        sb.append("version: ").append(version).append("\n");
        sb.append("state uuid: ").append(stateUUID).append("\n");
        sb.append("from_diff: ").append(wasReadFromDiff).append("\n");
        sb.append("meta data version: ").append(metadata.version()).append("\n");
        sb.append(TAB).append("coordination_metadata:\n");
        sb.append(TAB).append(TAB).append("term: ").append(coordinationMetadata().term()).append("\n");
        sb.append(TAB)
            .append(TAB)
            .append("last_committed_config: ")
            .append(coordinationMetadata().getLastCommittedConfiguration())
            .append("\n");
        sb.append(TAB)
            .append(TAB)
            .append("last_accepted_config: ")
            .append(coordinationMetadata().getLastAcceptedConfiguration())
            .append("\n");
        sb.append(TAB).append(TAB).append("voting tombstones: ").append(coordinationMetadata().getVotingConfigExclusions()).append("\n");
        for (IndexMetadata indexMetadata : metadata) {
            sb.append(TAB).append(indexMetadata.getIndex());
            sb.append(": v[")
                .append(indexMetadata.getVersion())
                .append("], mv[")
                .append(indexMetadata.getMappingVersion())
                .append("], sv[")
                .append(indexMetadata.getSettingsVersion())
                .append("], av[")
                .append(indexMetadata.getAliasesVersion())
                .append("]\n");
            for (int shard = 0; shard < indexMetadata.getNumberOfShards(); shard++) {
                sb.append(TAB).append(TAB).append(shard).append(": ");
                sb.append("p_term [").append(indexMetadata.primaryTerm(shard)).append("], ");
                sb.append("isa_ids ").append(indexMetadata.inSyncAllocationIds(shard)).append("\n");
            }
        }
        if (metadata.customs().isEmpty() == false) {
            sb.append("metadata customs:\n");
            for (final Map.Entry<String, Metadata.Custom> cursor : metadata.customs().entrySet()) {
                final String type = cursor.getKey();
                final Metadata.Custom custom = cursor.getValue();
                sb.append(TAB).append(type).append(": ").append(custom);
            }
            sb.append("\n");
        }
        sb.append(blocks());
        sb.append(nodes());
        sb.append(routingTable());
        sb.append(getRoutingNodes());
        if (customs.isEmpty() == false) {
            sb.append("customs:\n");
            for (Map.Entry<String, Custom> cursor : customs.entrySet()) {
                final String type = cursor.getKey();
                final Custom custom = cursor.getValue();
                sb.append(TAB).append(type).append(": ").append(custom);
            }
        }
        return sb.toString();
    }

    /**
     * a cluster state supersedes another state if they are from the same master and the version of this state is higher than that of the
     * other state.
     * <p>
     * In essence that means that all the changes from the other cluster state are also reflected by the current one
     */
    public boolean supersedes(ClusterState other) {
        return this.nodes().getMasterNodeId() != null
            && this.nodes().getMasterNodeId().equals(other.nodes().getMasterNodeId())
            && this.version() > other.version();

    }

    public enum Metric {
        VERSION("version"),
        MASTER_NODE("master_node"),
        BLOCKS("blocks"),
        NODES("nodes"),
        METADATA("metadata"),
        ROUTING_TABLE("routing_table"),
        ROUTING_NODES("routing_nodes"),
        CUSTOMS("customs");

        private static final Map<String, Metric> valueToEnum;

        static {
            valueToEnum = new HashMap<>();
            for (Metric metric : Metric.values()) {
                valueToEnum.put(metric.value, metric);
            }
        }

        private final String value;

        Metric(String value) {
            this.value = value;
        }

        public static EnumSet<Metric> parseString(String param, boolean ignoreUnknown) {
            String[] metrics = Strings.splitStringByCommaToArray(param);
            EnumSet<Metric> result = EnumSet.noneOf(Metric.class);
            for (String metric : metrics) {
                if ("_all".equals(metric)) {
                    result = EnumSet.allOf(Metric.class);
                    break;
                }
                Metric m = valueToEnum.get(metric);
                if (m == null) {
                    if (ignoreUnknown == false) {
                        throw new IllegalArgumentException("Unknown metric [" + metric + "]");
                    }
                } else {
                    result.add(m);
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        EnumSet<Metric> metrics = Metric.parseString(params.param("metric", "_all"), true);

        // always provide the cluster_uuid as part of the top-level response (also part of the metadata response)
        builder.field("cluster_uuid", metadata().clusterUUID());

        if (metrics.contains(Metric.VERSION)) {
            builder.field("version", version);
            builder.field("state_uuid", stateUUID);
        }

        if (metrics.contains(Metric.MASTER_NODE)) {
            builder.field("master_node", nodes().getMasterNodeId());
        }

        if (metrics.contains(Metric.BLOCKS)) {
            builder.startObject("blocks");

            if (blocks().global().isEmpty() == false) {
                builder.startObject("global");
                for (ClusterBlock block : blocks().global()) {
                    block.toXContent(builder, params);
                }
                builder.endObject();
            }

            if (blocks().indices().isEmpty() == false) {
                builder.startObject("indices");
                for (Map.Entry<String, Set<ClusterBlock>> entry : blocks().indices().entrySet()) {
                    builder.startObject(entry.getKey());
                    for (ClusterBlock block : entry.getValue()) {
                        block.toXContent(builder, params);
                    }
                    builder.endObject();
                }
                builder.endObject();
            }

            builder.endObject();
        }

        // nodes
        if (metrics.contains(Metric.NODES)) {
            builder.startObject("nodes");
            for (DiscoveryNode node : nodes) {
                node.toXContent(builder, params);
            }
            builder.endObject();
        }

        // meta data
        if (metrics.contains(Metric.METADATA)) {
            metadata.toXContent(builder, params);
        }

        // routing table
        if (metrics.contains(Metric.ROUTING_TABLE)) {
            builder.startObject("routing_table");
            builder.startObject("indices");
            for (IndexRoutingTable indexRoutingTable : routingTable()) {
                builder.startObject(indexRoutingTable.getIndex().getName());
                builder.startObject("shards");
                for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                    builder.startArray(Integer.toString(indexShardRoutingTable.shardId().id()));
                    for (ShardRouting shardRouting : indexShardRoutingTable) {
                        shardRouting.toXContent(builder, params);
                    }
                    builder.endArray();
                }
                builder.endObject();
                builder.endObject();
            }
            builder.endObject();
            builder.endObject();
        }

        // routing nodes
        if (metrics.contains(Metric.ROUTING_NODES)) {
            builder.startObject("routing_nodes");
            builder.startArray("unassigned");
            for (ShardRouting shardRouting : getRoutingNodes().unassigned()) {
                shardRouting.toXContent(builder, params);
            }
            builder.endArray();

            builder.startObject("nodes");
            for (RoutingNode routingNode : getRoutingNodes()) {
                builder.startArray(routingNode.nodeId() == null ? "null" : routingNode.nodeId());
                for (ShardRouting shardRouting : routingNode) {
                    shardRouting.toXContent(builder, params);
                }
                builder.endArray();
            }
            builder.endObject();

            builder.endObject();
        }
        if (metrics.contains(Metric.CUSTOMS)) {
            for (Map.Entry<String, Custom> cursor : customs.entrySet()) {
                builder.startObject(cursor.getKey());
                cursor.getValue().toXContent(builder, params);
                builder.endObject();
            }
        }

        return builder;
    }

    public static Builder builder(ClusterName clusterName) {
        return new Builder(clusterName);
    }

    public static Builder builder(ClusterState state) {
        return new Builder(state);
    }

    public static class Builder {

        private ClusterState previous;

        private final ClusterName clusterName;
        private long version = 0;
        private String uuid = UNKNOWN_UUID;
        private Metadata metadata = Metadata.EMPTY_METADATA;
        private RoutingTable routingTable = RoutingTable.EMPTY_ROUTING_TABLE;
        private DiscoveryNodes nodes = DiscoveryNodes.EMPTY_NODES;
        private ClusterBlocks blocks = ClusterBlocks.EMPTY_CLUSTER_BLOCK;
        private final ImmutableOpenMap.Builder<String, Custom> customs;
        private boolean fromDiff;

        public Builder(ClusterState state) {
            this.previous = state;
            this.clusterName = state.clusterName;
            this.version = state.version();
            this.uuid = state.stateUUID();
            this.nodes = state.nodes();
            this.routingTable = state.routingTable();
            this.metadata = state.metadata();
            this.blocks = state.blocks();
            this.customs = ImmutableOpenMap.builder(state.customs());
            this.fromDiff = false;
        }

        public Builder(ClusterName clusterName) {
            customs = ImmutableOpenMap.builder();
            this.clusterName = clusterName;
        }

        public Builder nodes(DiscoveryNodes.Builder nodesBuilder) {
            return nodes(nodesBuilder.build());
        }

        public Builder nodes(DiscoveryNodes nodes) {
            this.nodes = nodes;
            return this;
        }

        public DiscoveryNodes nodes() {
            return nodes;
        }

        public Builder routingTable(RoutingTable routingTable) {
            this.routingTable = routingTable;
            return this;
        }

        public Builder metadata(Metadata.Builder metadataBuilder) {
            return metadata(metadataBuilder.build());
        }

        public Builder metadata(Metadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder blocks(ClusterBlocks.Builder blocksBuilder) {
            return blocks(blocksBuilder.build());
        }

        public Builder blocks(ClusterBlocks blocks) {
            this.blocks = blocks;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder incrementVersion() {
            this.version = version + 1;
            this.uuid = UNKNOWN_UUID;
            return this;
        }

        public Builder stateUUID(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder putCustom(String type, Custom custom) {
            customs.put(type, Objects.requireNonNull(custom, type));
            return this;
        }

        public Builder removeCustom(String type) {
            customs.remove(type);
            return this;
        }

        public Builder customs(ImmutableOpenMap<String, Custom> customs) {
            customs.stream().forEach(entry -> Objects.requireNonNull(entry.getValue(), entry.getKey()));
            this.customs.putAll(customs);
            return this;
        }

        public Builder fromDiff(boolean fromDiff) {
            this.fromDiff = fromDiff;
            return this;
        }

        public ClusterState build() {
            if (UNKNOWN_UUID.equals(uuid)) {
                uuid = UUIDs.randomBase64UUID();
            }
            final RoutingNodes routingNodes;
            if (previous != null && routingTable.indicesRouting() == previous.routingTable.indicesRouting() && nodes == previous.nodes) {
                // routing table contents and nodes haven't changed so we can try to reuse the previous state's routing nodes which are
                // expensive to compute
                routingNodes = previous.routingNodes;
            } else {
                routingNodes = null;
            }
            return new ClusterState(
                clusterName,
                version,
                uuid,
                metadata,
                routingTable,
                nodes,
                blocks,
                customs.build(),
                fromDiff,
                routingNodes
            );
        }

        public static byte[] toBytes(ClusterState state) throws IOException {
            BytesStreamOutput os = new BytesStreamOutput();
            state.writeTo(os);
            return BytesReference.toBytes(os.bytes());
        }

        /**
         * @param data      input bytes
         * @param localNode used to set the local node in the cluster state.
         */
        public static ClusterState fromBytes(byte[] data, DiscoveryNode localNode, NamedWriteableRegistry registry) throws IOException {
            StreamInput in = new NamedWriteableAwareStreamInput(StreamInput.wrap(data), registry);
            return readFrom(in, localNode);

        }
    }

    @Override
    public Diff<ClusterState> diff(ClusterState previousState) {
        return new ClusterStateDiff(previousState, this);
    }

    public static Diff<ClusterState> readDiffFrom(StreamInput in, DiscoveryNode localNode) throws IOException {
        return new ClusterStateDiff(in, localNode);
    }

    public static ClusterState readFrom(StreamInput in, DiscoveryNode localNode) throws IOException {
        ClusterName clusterName = new ClusterName(in);
        Builder builder = new Builder(clusterName);
        builder.version = in.readLong();
        builder.uuid = in.readString();
        builder.metadata = Metadata.readFrom(in);
        builder.routingTable = RoutingTable.readFrom(in);
        builder.nodes = DiscoveryNodes.readFrom(in, localNode);
        builder.blocks = ClusterBlocks.readFrom(in);
        int customSize = in.readVInt();
        for (int i = 0; i < customSize; i++) {
            Custom customIndexMetadata = in.readNamedWriteable(Custom.class);
            builder.putCustom(customIndexMetadata.getWriteableName(), customIndexMetadata);
        }
        if (in.getVersion().before(Version.V_8_0_0)) {
            in.readVInt(); // used to be minimumMasterNodesOnPublishingMaster, which was used in 7.x for BWC with 6.x
        }
        return builder.build();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        clusterName.writeTo(out);
        out.writeLong(version);
        out.writeString(stateUUID);
        metadata.writeTo(out);
        routingTable.writeTo(out);
        nodes.writeTo(out);
        blocks.writeTo(out);
        VersionedNamedWriteable.writeVersionedWritables(out, customs);
        if (out.getVersion().before(Version.V_8_0_0)) {
            out.writeVInt(-1); // used to be minimumMasterNodesOnPublishingMaster, which was used in 7.x for BWC with 6.x
        }
    }

    private static class ClusterStateDiff implements Diff<ClusterState> {

        private final long toVersion;

        private final String fromUuid;

        private final String toUuid;

        private final ClusterName clusterName;

        private final Diff<RoutingTable> routingTable;

        private final Diff<DiscoveryNodes> nodes;

        private final Diff<Metadata> metadata;

        private final Diff<ClusterBlocks> blocks;

        private final Diff<ImmutableOpenMap<String, Custom>> customs;

        ClusterStateDiff(ClusterState before, ClusterState after) {
            fromUuid = before.stateUUID;
            toUuid = after.stateUUID;
            toVersion = after.version;
            clusterName = after.clusterName;
            routingTable = after.routingTable.diff(before.routingTable);
            nodes = after.nodes.diff(before.nodes);
            metadata = after.metadata.diff(before.metadata);
            blocks = after.blocks.diff(before.blocks);
            customs = DiffableUtils.diff(before.customs, after.customs, DiffableUtils.getStringKeySerializer(), CUSTOM_VALUE_SERIALIZER);
        }

        ClusterStateDiff(StreamInput in, DiscoveryNode localNode) throws IOException {
            clusterName = new ClusterName(in);
            fromUuid = in.readString();
            toUuid = in.readString();
            toVersion = in.readLong();
            routingTable = RoutingTable.readDiffFrom(in);
            nodes = DiscoveryNodes.readDiffFrom(in, localNode);
            metadata = Metadata.readDiffFrom(in);
            blocks = ClusterBlocks.readDiffFrom(in);
            customs = DiffableUtils.readImmutableOpenMapDiff(in, DiffableUtils.getStringKeySerializer(), CUSTOM_VALUE_SERIALIZER);
            if (in.getVersion().before(Version.V_8_0_0)) {
                in.readVInt(); // used to be minimumMasterNodesOnPublishingMaster, which was used in 7.x for BWC with 6.x
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            clusterName.writeTo(out);
            out.writeString(fromUuid);
            out.writeString(toUuid);
            out.writeLong(toVersion);
            routingTable.writeTo(out);
            nodes.writeTo(out);
            metadata.writeTo(out);
            blocks.writeTo(out);
            customs.writeTo(out);
            if (out.getVersion().before(Version.V_8_0_0)) {
                out.writeVInt(-1); // used to be minimumMasterNodesOnPublishingMaster, which was used in 7.x for BWC with 6.x
            }
        }

        @Override
        public ClusterState apply(ClusterState state) {
            Builder builder = new Builder(clusterName);
            if (toUuid.equals(state.stateUUID)) {
                // no need to read the rest - cluster state didn't change
                return state;
            }
            if (fromUuid.equals(state.stateUUID) == false) {
                throw new IncompatibleClusterStateVersionException(state.version, state.stateUUID, toVersion, fromUuid);
            }
            builder.stateUUID(toUuid);
            builder.version(toVersion);
            builder.routingTable(routingTable.apply(state.routingTable));
            builder.nodes(nodes.apply(state.nodes));
            builder.metadata(metadata.apply(state.metadata));
            builder.blocks(blocks.apply(state.blocks));
            builder.customs(customs.apply(state.customs));
            builder.fromDiff(true);
            return builder.build();
        }
    }
}
