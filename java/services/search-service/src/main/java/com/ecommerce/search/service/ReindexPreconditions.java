package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.ProductCardIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * What must be true before a full reindex is accepted ({@code WF-SEARCH-009} preconditions).
 *
 * <p>A red cluster cannot hold a second full index, and a cluster without room for one would
 * fill its disks halfway through the build and turn every shard read-only, which stops the
 * live indexer too. Yellow is accepted: it only means a replica is unassigned, which is the
 * permanent state of a single-node cluster, and the cluster still serves.
 *
 * <p>The disk check compares the cluster's free space with {@code min-free-disk-ratio} times the
 * primary store size of the indices being replaced. A cluster with replicas needs the ratio to
 * cover them as well.
 */
@Component
public class ReindexPreconditions {

    private static final Logger log = LoggerFactory.getLogger(ReindexPreconditions.class);

    private final ElasticsearchClient client;
    private final IndexAdmin indexAdmin;
    private final SearchProperties properties;

    public ReindexPreconditions(ElasticsearchClient client, IndexAdmin indexAdmin, SearchProperties properties) {
        this.client = client;
        this.indexAdmin = indexAdmin;
        this.properties = properties;
    }

    /**
     * @throws SearchException {@code REINDEX_PRECONDITION_FAILED} when the cluster cannot take a
     *                         reindex now; an unreachable cluster surfaces as the usual
     *                         Elasticsearch failure instead
     */
    public void check() throws IOException {
        if (!properties.lifecycle().preconditionEnabled()) {
            return;
        }
        HealthStatus status = client.cluster().health().status();
        if (status == HealthStatus.Red) {
            throw new SearchException(ErrorCode.REINDEX_PRECONDITION_FAILED,
                    "the Elasticsearch cluster status is red; a reindex needs room for a second full index");
        }

        long currentStore = indexAdmin.primaryStoreBytes(properties.aliases().products())
                + indexAdmin.primaryStoreBytes(properties.aliases().categories());
        long cards = indexAdmin.primaryStoreBytes(ProductCardIndex.ALIAS);
        // The first upgrade has no cards alias yet. Reserve space for that copy too.
        currentStore += cards > 0 ? cards : indexAdmin.primaryStoreBytes(properties.aliases().products());
        long free = freeDiskBytes();
        long required = (long) Math.ceil(currentStore * properties.lifecycle().minFreeDiskRatio());
        log.info("reindex precondition: cluster {}, free disk {} bytes, required {} bytes", status, free, required);
        if (free < required) {
            throw new SearchException(ErrorCode.REINDEX_PRECONDITION_FAILED,
                    "free disk " + free + " bytes is below the " + required + " bytes a second index needs "
                            + "(" + properties.lifecycle().minFreeDiskRatio() + " x the current primary store)");
        }
    }

    /** Free space summed over the nodes that hold data. */
    private long freeDiskBytes() throws IOException {
        var stats = client.nodes().stats(builder -> builder.metric("fs"));
        long free = 0;
        for (var node : stats.nodes().values()) {
            boolean dataNode = node.roles() == null || node.roles().isEmpty()
                    || node.roles().stream().anyMatch(role -> role.jsonValue().startsWith("data"));
            if (dataNode && node.fs() != null && node.fs().total() != null
                    && node.fs().total().availableInBytes() != null) {
                free += node.fs().total().availableInBytes();
            }
        }
        return free;
    }
}
