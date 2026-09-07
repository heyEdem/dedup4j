package com.edem.blobhelper.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates physical content metadata or retains the row that already owns the
 * supplied content identity.
 *
 * <p>The caller owns the entity manager and transaction. Inserts are flushed
 * explicitly so a unique-identity race can be reported before the caller's
 * commit. The Spring integration retries the operation after rollback when a
 * duplicate identity is reported. This service does not perform physical
 * storage operations.</p>
 */
public final class AssetContentMutationService {

    private final EntityManager entityManager;
    private final AssetContentRepository repository;

    public AssetContentMutationService(EntityManager entityManager) {
        this(entityManager, new AssetContentRepository(entityManager));
    }

    public AssetContentMutationService(
            EntityManager entityManager,
            AssetContentRepository repository
    ) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public AssetContent createOrRetain(AssetContent candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");

        return repository.findByIdentity(
                        candidate.getHashAlgorithm(),
                        candidate.getContentHash(),
                        candidate.getSizeBytes()
                )
                .map(existing -> retain(existing.getId()))
                .orElseGet(() -> insertOrRetry(candidate));
    }

    private AssetContent insertOrRetry(AssetContent candidate) {
        try {
            entityManager.persist(candidate);
            entityManager.flush();
            return candidate;
        } catch (PersistenceException failure) {
            if (!isDuplicateKeyFailure(failure)) {
                throw failure;
            }
            throw new DuplicateContentIdentityException(candidate, failure);
        }
    }

    private AssetContent retain(UUID assetContentId) {
        AssetContent existing = repository.findByIdForUpdate(assetContentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Asset content disappeared while retaining content: " + assetContentId
                ));
        existing.incrementRefCount();
        return existing;
    }

    private static boolean isDuplicateKeyFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
