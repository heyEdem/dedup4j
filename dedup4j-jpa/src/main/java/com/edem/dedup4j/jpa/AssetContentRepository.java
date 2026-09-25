package com.edem.dedup4j.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

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
        return Optional.ofNullable(entityManager.find(
                AssetContent.class,
                id,
                LockModeType.PESSIMISTIC_WRITE
        ));
    }

    public Optional<AssetContent> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(entityManager.find(AssetContent.class, id));
    }

    public List<AssetContent> findAll() {
        return entityManager.createQuery("select content from AssetContent content", AssetContent.class)
                .getResultList();
    }
}
