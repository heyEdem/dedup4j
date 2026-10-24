package com.edem.dedup4j.service;

import com.edem.dedup4j.core.exception.BlobValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTypePolicyTest {

    private final ContentTypePolicy policy = ContentTypePolicy.of(List.of("image/*", "application/pdf"));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not a type", "image/*", "*/*", "image/"})
    void emptyAllowListAcceptsAnyDeclaredType(String contentType) {
        ContentTypePolicy allowAll = ContentTypePolicy.of(List.of());

        assertTrue(allowAll.allowsAll());
        assertDoesNotThrow(() -> allowAll.check(contentType));
    }

    @Test
    void allowAllAcceptsAnyDeclaredType() {
        assertTrue(ContentTypePolicy.allowAll().allowsAll());
        assertDoesNotThrow(() -> ContentTypePolicy.allowAll().check(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/pdf",
            "image/png",
            "image/svg+xml",
            "IMAGE/PNG",
            "Application/PDF",
            "  image/jpeg  ",
            "application/pdf; charset=binary",
            "image/png;q=0.9"
    })
    void acceptsMatchingDeclaredTypes(String contentType) {
        assertFalse(policy.allowsAll());
        assertDoesNotThrow(() -> policy.check(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/pdfx", "application/x-pdf", "imagex/png", "video/mp4"})
    void rejectsDeclaredTypesOutsideTheAllowList(String contentType) {
        BlobValidationException ex = assertThrows(BlobValidationException.class, () -> policy.check(contentType));

        assertTrue(ex.getMessage().contains("not allowed"), ex.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "image", "image/", "/png", "image/*", "*/*", "image/png/extra", "ima ge/png", "; charset=utf-8"})
    void rejectsMissingMalformedOrWildcardDeclaredTypes(String contentType) {
        assertThrows(BlobValidationException.class, () -> policy.check(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "image", "image/", "/png", "*/*", "*/png", "image/png; charset=utf-8", "ima ge/png", "image/p*"})
    void rejectsMalformedAllowListEntries(String entry) {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ContentTypePolicy.of(List.of("application/pdf", entry))
        );

        assertTrue(ex.getMessage().contains("allowed-content-types"), ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "image/png\u0000",
            "\u0000image/png",
            "image/png\n",
            "image/png\u00A0",
            "application/vnd.android.pac\u212Aage-archive"
    })
    void rejectsDeclaredTypesThatOnlyMatchAfterNonAsciiOrControlNormalization(String contentType) {
        ContentTypePolicy exact = ContentTypePolicy.of(
                List.of("image/png", "application/vnd.android.package-archive"));

        assertThrows(BlobValidationException.class, () -> exact.check(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png\u0000", "application/vnd.android.pac\u212Aage-archive", "image/png\n"})
    void rejectsAllowListEntriesThatOnlyParseAfterNonAsciiOrControlNormalization(String entry) {
        assertThrows(IllegalArgumentException.class, () -> ContentTypePolicy.of(List.of(entry)));
    }

    @Test
    void acceptsTabAroundDeclaredType() {
        assertDoesNotThrow(() -> policy.check("\timage/png ; charset=binary"));
    }

    @Test
    void rejectsNullAllowList() {
        assertThrows(NullPointerException.class, () -> ContentTypePolicy.of(null));
    }
}
