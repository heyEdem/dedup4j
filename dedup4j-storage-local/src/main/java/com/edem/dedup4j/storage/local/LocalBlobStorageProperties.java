package com.edem.dedup4j.storage.local;

import java.nio.file.Path;
import java.util.Objects;

public class LocalBlobStorageProperties {

    private Path rootDirectory = Path.of("dedup4j-storage");

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
    }
}
