package com.edem.dedup4j.management;

import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ContentOrder;
import com.edem.dedup4j.reconcile.ContentReferenceCountSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Read-only content listing shared by the management API and the embedded dashboard. */
public class ContentCatalog {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final AssetContentRepository repository;
    private final ContentReferenceCountSource references;

    public ContentCatalog(ObjectProvider<AssetContentRepository> repository,
                          ObjectProvider<ContentReferenceCountSource> references) {
        this.repository = repository.getIfAvailable();
        this.references = references.getIfUnique();
    }

    /** Rows that still have references. Rejects an unknown sort or a negative or out-of-range page with 400. */
    public Dedup4jManagementSnapshot.ContentPage page(String sort, Integer page, Integer size) {
        ContentOrder order = order(sort);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        long offset = (long) pageNumber * pageSize;
        if (pageNumber < 0 || offset > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be between 0 and the last page");
        }
        if (repository == null) {
            return new Dedup4jManagementSnapshot.ContentPage(List.of(), pageNumber, pageSize, 0);
        }
        List<Dedup4jManagementSnapshot.Content> items = repository.findReferencedPage(order, (int) offset, pageSize)
                .stream()
                .map(content -> new Dedup4jManagementSnapshot.Content(content.getId(), content.getHashAlgorithm(),
                        content.getContentHash(), content.getSizeBytes(), content.getContentType(),
                        content.getRefCount()))
                .toList();
        return new Dedup4jManagementSnapshot.ContentPage(items, pageNumber, pageSize, repository.countReferenced());
    }

    /** Any row, released ones included. */
    public Optional<Dedup4jManagementSnapshot.ContentDetail> find(UUID id) {
        if (repository == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(this::detail);
    }

    private Dedup4jManagementSnapshot.ContentDetail detail(AssetContent content) {
        Long logical = references == null ? null : references.countLogicalReferences(content.getId());
        return new Dedup4jManagementSnapshot.ContentDetail(content.getId(), content.getHashAlgorithm(),
                content.getContentHash(), content.getSizeBytes(), content.getContentType(),
                content.getOriginalExtension(), content.getObjectKey(), content.getStorageProvider(),
                content.getBucketOrContainer(), content.getRefCount(), logical, content.getCreatedAt(),
                content.getUpdatedAt(), content.getObjectDeletedAt());
    }

    private static ContentOrder order(String sort) {
        if (sort == null || sort.equalsIgnoreCase("references")) {
            return ContentOrder.REFERENCES;
        }
        if (sort.toLowerCase(Locale.ROOT).equals("size")) {
            return ContentOrder.SIZE;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be 'references' or 'size'");
    }
}
