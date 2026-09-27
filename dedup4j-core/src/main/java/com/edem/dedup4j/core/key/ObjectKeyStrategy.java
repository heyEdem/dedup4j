package com.edem.dedup4j.core.key;

import com.edem.dedup4j.core.hash.ContentHash;

public interface ObjectKeyStrategy {

    String generateKey(ContentHash contentHash);
}
