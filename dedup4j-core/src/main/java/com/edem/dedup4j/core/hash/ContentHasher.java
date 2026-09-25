package com.edem.dedup4j.core.hash;

import java.io.IOException;
import java.io.InputStream;

public interface ContentHasher {

    ContentHash hash(InputStream inputStream) throws IOException;
}
