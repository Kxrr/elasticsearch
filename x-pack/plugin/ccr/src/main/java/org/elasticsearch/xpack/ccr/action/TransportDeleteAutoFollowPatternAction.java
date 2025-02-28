/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ccr.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.ccr.AutoFollowMetadata;
import org.elasticsearch.xpack.core.ccr.action.DeleteAutoFollowPatternAction;

public class TransportDeleteAutoFollowPatternAction extends AcknowledgedTransportMasterNodeAction<DeleteAutoFollowPatternAction.Request> {

    @Inject
    public TransportDeleteAutoFollowPatternAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            DeleteAutoFollowPatternAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            DeleteAutoFollowPatternAction.Request::new,
            indexNameExpressionResolver,
            ThreadPool.Names.SAME
        );
    }

    @Override
    protected void masterOperation(
        Task task,
        DeleteAutoFollowPatternAction.Request request,
        ClusterState state,
        ActionListener<AcknowledgedResponse> listener
    ) {
        clusterService.submitStateUpdateTask(
            "delete-auto-follow-pattern-" + request.getName(),
            new AckedClusterStateUpdateTask(request, listener) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return innerDelete(request, currentState);
                }
            },
            ClusterStateTaskExecutor.unbatched()
        );
    }

    static ClusterState innerDelete(DeleteAutoFollowPatternAction.Request request, ClusterState currentState) {
        AutoFollowMetadata currentAutoFollowMetadata = currentState.metadata().custom(AutoFollowMetadata.TYPE);
        if (currentAutoFollowMetadata == null || currentAutoFollowMetadata.getPatterns().get(request.getName()) == null) {
            throw new ResourceNotFoundException("auto-follow pattern [{}] is missing", request.getName());
        }

        AutoFollowMetadata newAutoFollowMetadata = removePattern(currentAutoFollowMetadata, request.getName());

        return ClusterState.builder(currentState)
            .metadata(Metadata.builder(currentState.getMetadata()).putCustom(AutoFollowMetadata.TYPE, newAutoFollowMetadata))
            .build();
    }

    private static AutoFollowMetadata removePattern(AutoFollowMetadata metadata, String name) {
        return new AutoFollowMetadata(
            Maps.copyMapWithRemovedEntry(metadata.getPatterns(), name),
            Maps.copyMapWithRemovedEntry(metadata.getFollowedLeaderIndexUUIDs(), name),
            Maps.copyMapWithRemovedEntry(metadata.getHeaders(), name)
        );
    }

    @Override
    protected ClusterBlockException checkBlock(DeleteAutoFollowPatternAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}
