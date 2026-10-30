package com.edem.dedup4j.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence operations for physical content metadata.
 *
 * <p>The supplied entity manager and its transaction are owned by the caller.
 * In particular, a pessimistic lock remains held for the caller's transaction
 * so reference-count services can safely mutate the locked entity.</p>
 */
public final class AssetContentRepository {

    private final EntityManager entityManager;

    public AssetContentRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    /** Whether any content row, referenced or released, points at this object key. */
    public boolean existsByObjectKey(String objectKey) {
        return !entityManager.createQuery("""
                        select content.id
                        from AssetContent content
                        where content.objectKey = :objectKey
                        """, UUID.class)
                .setParameter("objectKey", objectKey)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    public Optional<AssetContent> findByIdentity(
            String hashAlgorithm,
            String contentHash,
            long sizeBytes
    ) {
        return entityManager.createQuery("""
                        select content
                        from AssetContent content
                        where content.hashAlgorithm = :hashAlgorithm
                          and content.contentHash = :contentHash
                          and content.sizeBytes = :sizeBytes
                        """, AssetContent.class)
                .setParameter("hashAlgorithm", hashAlgorithm)
                .setParameter("contentHash", contentHash)
                .setParameter("sizeBytes", sizeBytes)
                .getResultStream()
                .findFirst();
    }

    public Optional<AssetContent> findByIdForUpdate(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        AssetContent content = entityManager.find(AssetContent.class, id);
        if (content == null) {
            return Optional.empty();
        }
        // The row may already sit in this persistence context from an unlocked read; locking it
        // there would fail on a newer version. Flush pending changes, then re-read under the lock.
        entityManager.flush();
        entityManager.refresh(content, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(content);
    }

    public Optional<AssetContent> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(entityManager.find(AssetContent.class, id));
    }

    /**
     * Returns up to {@code limit} rows ordered by ID, starting after {@code afterId}, or from the
     * first row when it is null. Keyset paging neither skips nor repeats rows when others are added.
     */
    public List<AssetContent> findPageAfter(UUID afterId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        TypedQuery<AssetContent> query = afterId == null
                ? entityManager.createQuery(
                        "select content from AssetContent content order by content.id", AssetContent.class)
                : entityManager.createQuery("""
                                select content
                                from AssetContent content
                                where content.id > :afterId
                                order by content.id
                                """, AssetContent.class)
                        .setParameter("afterId", afterId);
        return query.setMaxResults(limit).getResultList();
    }

    /** Keyset page of released rows (zero references), ordered by ID, as {@link #findPageAfter}. */
    public List<AssetContent> findReleasedPageAfter(UUID afterId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        TypedQuery<AssetContent> query = afterId == null
                ? entityManager.createQuery("""
                                select content
                                from AssetContent content
                                where content.refCount = 0
                                order by content.id
                                """, AssetContent.class)
                : entityManager.createQuery("""
                                select content
                                from AssetContent content
                                where content.refCount = 0 and content.id > :afterId
                                order by content.id
                                """, AssetContent.class)
                        .setParameter("afterId", afterId);
        return query.setMaxResults(limit).getResultList();
    }

    public List<AssetContent> findAll() {
        return entityManager.createQuery("select content from AssetContent content", AssetContent.class)
                .getResultList();
    }
}
