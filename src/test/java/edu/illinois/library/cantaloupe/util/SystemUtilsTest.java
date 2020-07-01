package edu.illinois.library.cantaloupe.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class SystemUtilsTest {

    @Test
    public void testGetJavaMajorVersion() {
        // This will be tested indirectly in testParseJavaMajorVersion().
        assertTrue(SystemUtils.isJava9OrAbove());
    }

    @Test
    public void testIsALPNAvailable() {
        boolean result = SystemUtils.isALPNAvailable();
        if (SystemUtils.isJava9OrAbove()) {
            assertTrue(result);
        } else {
            assertFalse(result);
        }
    }

}
