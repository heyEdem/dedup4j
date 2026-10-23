package com.edem.dedup4j.service;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable allow-list of upload content types, parsed once from
 * {@code dedup4j.deduplication.allowed-content-types}.
 *
 * <p>Entries are exact {@code type/subtype} values or {@code type/*} wildcards. An empty list
 * allows every upload, including one without a content type. Otherwise the declared type must
 * be present and match an entry; parameters such as {@code charset} are ignored and matching
 * is case-insensitive. Only the declared type is checked, never the content bytes.</p>
 */
public final class ContentTypePolicy {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9!#$&^_.+-]+");
    private static final ContentTypePolicy ALLOW_ALL = new ContentTypePolicy(List.of());

    private final List<String> allowed;

    private ContentTypePolicy(List<String> allowed) {
        this.allowed = allowed;
    }

    public static ContentTypePolicy allowAll() {
        return ALLOW_ALL;
    }

    /**
     * Parses the allow-list.
     *
     * @throws IllegalArgumentException if an entry is not {@code type/subtype} or {@code type/*}
     */
    public static ContentTypePolicy of(Collection<String> allowedContentTypes) {
        Objects.requireNonNull(allowedContentTypes, "allowedContentTypes must not be null");
        if (allowedContentTypes.isEmpty()) {
            return ALLOW_ALL;
        }
        List<String> allowed = allowedContentTypes.stream()
                .map(ContentTypePolicy::parseEntry)
                .toList();
        return new ContentTypePolicy(allowed);
    }

    public boolean allowsAll() {
        return allowed.isEmpty();
    }

    /** Throws {@link BlobValidationException} unless the declared type is allowed. */
    public void check(String contentType) {
        if (allowsAll()) {
            return;
        }
        String baseType = baseType(contentType);
        if (baseType == null) {
            throw new BlobValidationException("Content type '" + contentType + "' is not allowed: "
                    + "a concrete type/subtype is required");
        }
        String wildcard = baseType.substring(0, baseType.indexOf('/') + 1) + "*";
        if (!allowed.contains(baseType) && !allowed.contains(wildcard)) {
            throw new BlobValidationException("Content type '" + contentType + "' is not allowed");
        }
    }

    private static String parseEntry(String entry) {
        String trimmed = entry == null ? "" : trimWhitespace(entry);
        int slash = trimmed.indexOf('/');
        if (slash > 0) {
            String type = trimmed.substring(0, slash);
            String subtype = trimmed.substring(slash + 1);
            if (TOKEN.matcher(type).matches()
                    && (subtype.equals("*") || TOKEN.matcher(subtype).matches())) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalArgumentException("Invalid dedup4j.deduplication.allowed-content-types entry '"
                + entry + "': expected type/subtype or type/*");
    }

    /** Returns the lower-case {@code type/subtype} without parameters, or null if malformed. */
    private static String baseType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String base = trimWhitespace(semicolon < 0 ? contentType : contentType.substring(0, semicolon));
        int slash = base.indexOf('/');
        if (slash <= 0
                || !TOKEN.matcher(base.substring(0, slash)).matches()
                || !TOKEN.matcher(base.substring(slash + 1)).matches()) {
            return null;
        }
        return base.toLowerCase(Locale.ROOT);
    }

    /**
     * Strips only spaces and tabs, the whitespace allowed around a media type. Tokens are
     * validated before lower-casing so control and non-ASCII characters never normalize into
     * a match.
     */
    private static String trimWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpaceOrTab(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpaceOrTab(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpaceOrTab(char c) {
        return c == ' ' || c == '\t';
    }
}
