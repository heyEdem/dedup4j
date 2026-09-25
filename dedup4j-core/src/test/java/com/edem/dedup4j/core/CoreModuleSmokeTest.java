package com.edem.dedup4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoreModuleSmokeTest {

    @Test
    void coreModuleTestsRunInExpectedPackage() {
        assertEquals("com.edem.dedup4j.core", CoreModuleSmokeTest.class.getPackageName());
    }
}
