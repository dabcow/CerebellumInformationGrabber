package org.cerebellum.morphometry;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The plugin's own version, recorded in every export so results can always be traced back to
 * the exact algorithm that produced them. Maven writes it into {@code build.properties} at build
 * time (resource filtering); running from an IDE without that step reports "development".
 */
public final class BuildInfo {

    public static final String NAME = "Cerebellar Layer Quantification Plugin";

    private static final String VERSION = loadVersion();

    private BuildInfo() {
    }

    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("build.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "");
                if (!v.isEmpty() && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "development";
    }
}
