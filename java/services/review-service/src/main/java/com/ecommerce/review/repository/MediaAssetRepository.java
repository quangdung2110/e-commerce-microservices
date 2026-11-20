package com.ecommerce.review.repository;

import com.ecommerce.review.domain.MediaAsset;
import com.ecommerce.review.domain.MediaAssetState;
import com.ecommerce.review.domain.MediaDeletionReason;
import com.ecommerce.review.domain.MediaType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code review_media_assets}: the lifecycle of one uploaded object.
 *
 * <p>No method here performs a storage call. Every route into {@code DELETE_PENDING}
 * only records the intent with its reason, and the worker deletes the bytes after that
 * transaction commits (ADR-011).
 */
@Repository
public class MediaAssetRepository {

    private static final String COLUMNS = """
            id, owner_user_id, owner_erased_at, object_key, media_type, upload_state,
            content_type, byte_size, checksum, expires_at, verified_at, attached_at,
            deletion_reason, delete_requested_at, deleted_at, created_at, updated_at
            """;

    private static final RowMapper<MediaAsset> MAPPER = (rs, row) -> new MediaAsset(
            Rows.uuid(rs, "id"),
            Rows.uuid(rs, "owner_user_id"),
            Rows.instant(rs, "owner_erased_at"),
            rs.getString("object_key"),
            Rows.enumValue(rs, "media_type", MediaType.class),
            Rows.enumValue(rs, "upload_state", MediaAssetState.class),
            rs.getString("content_type"),
            Rows.longOrNull(rs, "byte_size"),
            rs.getString("checksum"),
            Rows.instant(rs, "expires_at"),
            Rows.instant(rs, "verified_at"),
            Rows.instant(rs, "attached_at"),
            Rows.enumValue(rs, "deletion_reason", MediaDeletionReason.class),
            Rows.instant(rs, "delete_requested_at"),
            Rows.instant(rs, "deleted_at"),
            Rows.instant(rs, "created_at"),
            Rows.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public MediaAssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the {@code ISSUED} asset behind a signed upload instruction. */
    public MediaAsset insertIssued(UUID ownerUserId,
                                   String objectKey,
                                   MediaType mediaType,
                                   String contentType,
                                   long declaredByteSize,
                                   String checksum,
                                   Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO review_media_assets
                    (owner_user_id, object_key, media_type, upload_state, content_type,
                     byte_size, checksum, expires_at)
                VALUES (?, ?, ?::review_media_type, 'ISSUED', ?, ?, ?, ?)
                RETURNING
                """ + COLUMNS)
                .param(ownerUserId)
                .param(objectKey)
                .param(mediaType.name())
                .param(contentType)
                .param(declaredByteSize)
                .param(checksum)
                .param(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .query(MAPPER).single();
    }

    public Optional<MediaAsset> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM review_media_assets WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    /**
     * Locks one asset for attachment.
     *
     * <p>Attachment and the expiry sweep lock the same row, so exactly one wins: an
     * attach that commits {@code ATTACHED} first removes the asset from the sweep, and a
     * sweep that commits {@code DELETE_PENDING} first makes the attach reject it as
     * unusable (ADR-011).
     */
    public Optional<MediaAsset> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM review_media_assets WHERE id = ? FOR UPDATE")
                .param(id).query(MAPPER).optional();
    }

    /** How many assets the caller is holding without attaching them. */
    public long countUnattachedByOwner(UUID ownerUserId) {
        return jdbc.sql("""
                SELECT count(*) FROM review_media_assets
                 WHERE owner_user_id = ? AND upload_state IN ('ISSUED', 'VERIFIED')
                """).param(ownerUserId).query(Long.class).single();
    }

    /**
     * The declared bytes those assets reserve.
     *
     * <p>Without a byte quota next to the count quota, direct upload becomes unbounded
     * storage: eighteen video slots are a very different amount of object storage from
     * eighteen thumbnails ({@code docs/review/data.md 4}).
     */
    public long sumUnattachedBytesByOwner(UUID ownerUserId) {
        return jdbc.sql("""
                SELECT coalesce(sum(byte_size), 0) FROM review_media_assets
                 WHERE owner_user_id = ? AND upload_state IN ('ISSUED', 'VERIFIED')
                """).param(ownerUserId).query(Long.class).single();
    }

    /**
     * Moves a verified object to {@code ATTACHED} inside the create or edit transaction.
     *
     * <p>The transition through {@code VERIFIED} is not skipped: the CHECK requires
     * {@code verified_at} on both states, and it is the record of the metadata check
     * that ran before the transaction opened.
     */
    public void markVerifiedAndAttached(UUID assetId,
                                        String contentType,
                                        long byteSize,
                                        String checksum,
                                        Instant now) {
        jdbc.sql("""
                UPDATE review_media_assets
                   SET upload_state = 'ATTACHED', verified_at = ?, attached_at = ?,
                       content_type = ?, byte_size = ?,
                       checksum = coalesce(?, checksum)
                 WHERE id = ? AND upload_state IN ('ISSUED', 'VERIFIED')
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(contentType)
                .param(byteSize)
                .param(checksum)
                .param(assetId)
                .update();
    }

    /**
     * Queues one or more assets for storage cleanup with the reason that detached them.
     *
     * <p>{@code REPLACED} for a media replacement, {@code TAKEDOWN} for a legal takedown
     * and {@code UNATTACHED_EXPIRED} for the deadline sweep. The reason is not
     * cosmetic: the cleanup metric and the runbook are both keyed on it.
     */
    public int queueDeletion(Collection<UUID> assetIds, MediaDeletionReason reason, Instant now) {
        if (assetIds.isEmpty()) {
            return 0;
        }
        List<Object> params = new ArrayList<>();
        params.add(reason.name());
        params.add(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        params.addAll(assetIds);
        return jdbc.sql("""
                UPDATE review_media_assets
                   SET upload_state = 'DELETE_PENDING',
                       deletion_reason = ?::review_media_deletion_reason,
                       delete_requested_at = ?
                 WHERE id IN (%s)
                   AND upload_state IN ('ISSUED', 'VERIFIED', 'ATTACHED')
                """.formatted(Placeholders.of(assetIds.size())))
                .params(params)
                .update();
    }

    /**
     * WF-REVIEW-011: every {@code ISSUED} or {@code VERIFIED} asset past its attachment
     * deadline enters cleanup. An {@code ISSUED} asset may never have carried an object
     * at all, which is why {@code verified_at} stays nullable in the cleanup states.
     */
    public int sweepExpiredUnattached(Instant now, int batchSize) {
        return jdbc.sql("""
                UPDATE review_media_assets
                   SET upload_state = 'DELETE_PENDING',
                       deletion_reason = 'UNATTACHED_EXPIRED',
                       delete_requested_at = ?
                 WHERE id IN (
                     SELECT id FROM review_media_assets
                      WHERE upload_state IN ('ISSUED', 'VERIFIED') AND expires_at <= ?
                      ORDER BY expires_at
                      LIMIT ?
                      FOR UPDATE SKIP LOCKED)
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(batchSize)
                .update();
    }

    /**
     * One keyset page of the cleanup backlog, oldest request first.
     *
     * <p>Paging by {@code (delete_requested_at, id)} lets one pass visit every pending asset
     * exactly once: an asset whose storage call fails stays {@code DELETE_PENDING} for the
     * next pass but is not offered again in this one, so a run of failing assets at the head
     * of the queue cannot starve the ones behind it.
     *
     * @param afterRequestedAt the position of the last asset of the previous page, or
     *                         {@code null} for the first page
     */
    public List<MediaAsset> listDeletePending(Instant afterRequestedAt, UUID afterId, int batchSize) {
        String sql = "SELECT " + COLUMNS + """
                 FROM review_media_assets
                 WHERE upload_state = 'DELETE_PENDING'
                """
                + (afterRequestedAt == null ? "" : " AND (delete_requested_at, id) > (?, ?)")
                + " ORDER BY delete_requested_at, id LIMIT ?";
        var spec = jdbc.sql(sql);
        if (afterRequestedAt != null) {
            spec = spec.param(OffsetDateTime.ofInstant(afterRequestedAt, ZoneOffset.UTC)).param(afterId);
        }
        return spec.param(batchSize).query(MAPPER).list();
    }

    /**
     * Marks an asset deleted, and only ever after storage confirmed it.
     *
     * <p>A storage "object not found" counts as that confirmation: an
     * {@code UNATTACHED_EXPIRED} asset may never have been uploaded, and without this it
     * would retry forever ({@code docs/review/operations.md 6}).
     */
    public void markDeleted(UUID assetId, Instant now) {
        jdbc.sql("""
                UPDATE review_media_assets
                   SET upload_state = 'DELETED', deleted_at = ?
                 WHERE id = ? AND upload_state = 'DELETE_PENDING'
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(assetId)
                .update();
    }

    public long countDeletePending() {
        return jdbc.sql("SELECT count(*) FROM review_media_assets WHERE upload_state = 'DELETE_PENDING'")
                .query(Long.class).single();
    }

    /**
     * The privacy hard purge; both columns of the pair are written together so
     * {@code ck_media_asset_owner_erasure_consistent} cannot reject the statement. The
     * opaque {@code object_key} is retained: it identifies no person.
     */
    public int purgeOwner(UUID subjectUserId, Instant now) {
        return jdbc.sql("""
                UPDATE review_media_assets
                   SET owner_user_id = NULL, owner_erased_at = ?
                 WHERE owner_user_id = ?
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(subjectUserId)
                .update();
    }
}
