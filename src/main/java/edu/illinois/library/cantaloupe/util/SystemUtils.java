package edu.illinois.library.cantaloupe.util;

public final class SystemUtils {

    /**
     * @return Java major version such as <code>8</code>, <code>9</code>, etc.
     */
    public static boolean isJava9OrAbove() {
        final String versionStr = System.getProperty("java.version");
        return !versionStr.startsWith("1.");
    }

    /**
     * ALPN is built into Java 9 and later.
     */
    public static boolean isALPNAvailable() {
        return isJava9OrAbove();
    }

    private SystemUtils() {}

}
