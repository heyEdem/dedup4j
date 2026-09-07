package com.edem.blobhelper.service;

import com.edem.blobhelper.core.model.BlobReference;
import com.edem.blobhelper.core.model.StoreBlobCommand;
import com.edem.blobhelper.core.storage.BlobResource;
import com.edem.blobhelper.jpa.AssetContent;
import com.edem.blobhelper.jpa.AssetContentRepository;
import com.edem.blobhelper.jpa.DuplicateContentIdentityException;
import com.edem.blobhelper.jpa.ReferenceCountService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class SpringTransactionalBlobDeduplicationService implements BlobDeduplicationService {

    private final BlobDeduplicationService delegate;
    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final TransactionTemplate transactions;

    public SpringTransactionalBlobDeduplicationService(
            BlobDeduplicationService delegate,
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.referenceCountService = Objects.requireNonNull(
                referenceCountService, "referenceCountService must not be null"
        );
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public BlobReference store(StoreBlobCommand command) {
        try {
            return requiredResult(() -> delegate.store(command));
        } catch (DuplicateContentIdentityException race) {
            return requiredResult(() -> retainWinner(race));
        }
    }

    private BlobReference retainWinner(DuplicateContentIdentityException race) {
        AssetContent winner = repository.findByIdentity(
                        race.getHashAlgorithm(), race.getContentHash(), race.getSizeBytes()
                )
                .orElseThrow(() -> race);
        referenceCountService.retain(winner.getId());
        return BlobReferences.from(winner, true);
    }

    @Override
    public void retain(UUID assetContentId) {
        transactions.executeWithoutResult(status -> delegate.retain(assetContentId));
    }

    @Override
    public void release(UUID assetContentId) {
        transactions.executeWithoutResult(status -> delegate.release(assetContentId));
    }

    @Override
    public BlobResource get(UUID assetContentId) {
        return requiredResult(() -> delegate.get(assetContentId));
    }

    private <T> T requiredResult(Supplier<T> action) {
        return Objects.requireNonNull(transactions.execute(status -> action.get()));
    }
}
