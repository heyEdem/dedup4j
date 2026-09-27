package com.edem.dedup4j.core.key;

import com.edem.dedup4j.core.hash.ContentHash;

import java.util.Locale;
import java.util.Objects;

public class HashObjectKeyStrategy implements ObjectKeyStrategy {

    private final String prefix;

    public HashObjectKeyStrategy(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
    }

    @Override
    public String generateKey(ContentHash contentHash) {
        Objects.requireNonNull(contentHash, "contentHash must not be null");

        String algorithm = contentHash.algorithm().toLowerCase(Locale.ROOT);
        String hash = contentHash.hash();
        String hashPrefix = hash.substring(0, 2);
        String hashKey = algorithm + "/" + hashPrefix + "/" + hash;

        if (prefix.isEmpty()) {
            return hashKey;
        }

        return prefix + "/" + hashKey;
    }
}
