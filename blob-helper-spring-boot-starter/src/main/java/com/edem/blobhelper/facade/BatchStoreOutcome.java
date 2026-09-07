package com.edem.blobhelper.facade;

public sealed interface BatchStoreOutcome permits BlobStoreSuccess, BlobStoreFailure {

    int index();

    String filename();
}
