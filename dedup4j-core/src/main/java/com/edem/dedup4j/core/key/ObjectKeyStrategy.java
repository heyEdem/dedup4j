package com.edem.dedup4j.core.key;

import com.edem.dedup4j.core.hash.ContentHash;

/**
 * Maps content identity to its storage key. Distinct identities must get distinct keys: releasing
 * content deletes its key, and cleanup assumes no other content's object lives there.
 */
public interface ObjectKeyStrategy {

    String generateKey(ContentHash contentHash);
}
