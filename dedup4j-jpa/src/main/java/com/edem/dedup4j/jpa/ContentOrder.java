package com.edem.dedup4j.jpa;

/** Sort order for {@link AssetContentRepository#findReferencedPage}, largest first, ties broken by ID. */
public enum ContentOrder {
    REFERENCES("content.refCount desc, content.id"),
    SIZE("content.sizeBytes desc, content.id");

    final String jpql;

    ContentOrder(String jpql) {
        this.jpql = jpql;
    }
}
