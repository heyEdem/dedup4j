package com.edem.dedup4j.facade;

public sealed interface BatchStoreOutcome permits BlobStoreSuccess, BlobStoreFailure {

    int index();

    String filename();
}
