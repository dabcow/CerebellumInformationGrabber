package org.cerebellum.morphometry;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;

import java.util.regex.Pattern;

/**
 * Marks ROIs and overlay items created by this plugin, so they can be told apart from the user's
 * own input ROIs.
 *
 * <p>This matters because the plugin adds its measurement ROIs to the same ROI Manager the user's
 * input ROIs live in. Names such as {@code "Granular Layer"} or {@code "2Cb_Granular"} contain the
 * very words the input-name matcher looks for ("granular", a leading instance number, "cb"), so
 * without a marker a second run on the same ROI Manager would mistake the first run's output for
 * extra input and fail validation.</p>
 *
 * <p>The marker is a ROI property, which ImageJ saves with the ROI in {@code .roi} files and ROI
 * Manager {@code .zip} sets, so it survives a save and reload.</p>
 */
public final class PluginOutput {

    /** ROI property key used as the marker. */
    public static final String PROPERTY = "cerebellar-morphometry-output";

    /**
     * Names produced by version 1.0.0, which did not set the marker: an {@code "[N] "} instance
     * prefix, or a {@code <lobule>_Granular / _Molecular / _Purkinje} per-section ROI.
     */
    private static final Pattern LEGACY_OUTPUT_NAME = Pattern.compile(
            "^\\[\\d+\\] .*|^(\\d+(/\\d+)?Cb|Section \\d+)_(Granular|Molecular|Purkinje)$",
            Pattern.CASE_INSENSITIVE);

    private PluginOutput() {
    }

    /**
     * Marks {@code roi} as created by this plugin and returns it. Only call this on a ROI the
     * plugin constructed itself: see {@link #markedCopy} for copying a user's ROI.
     */
    public static <T extends Roi> T mark(T roi) {
        roi.setProperty(PROPERTY, "true");
        return roi;
    }

    /**
     * A named, marked copy of {@code roi} that shares no state with it.
     *
     * <p>{@link Roi#clone()} is shallow: the clone shares the original's property table, so marking
     * a clone of a user's ROI could mark the user's ROI too &mdash; after which the next run would
     * skip it as plugin output. Line and area ROIs are therefore rebuilt from their coordinates.</p>
     */
    public static Roi markedCopy(Roi roi, String name) {
        Roi copy;
        if (roi instanceof ShapeRoi) {
            copy = new ShapeRoi(roi);
        } else if (roi instanceof PolygonRoi) {
            copy = new PolygonRoi(roi.getFloatPolygon(), roi.getType());
        } else {
            copy = new ShapeRoi(roi);
        }
        copy.setName(name);
        return mark(copy);
    }

    /** True if {@code roi} was created by this plugin (this version, or by name, 1.0.0). */
    public static boolean isOutput(Roi roi) {
        if (roi == null) {
            return false;
        }
        if ("true".equals(roi.getProperty(PROPERTY))) {
            return true;
        }
        String name = roi.getName();
        return name != null && LEGACY_OUTPUT_NAME.matcher(name.trim()).matches();
    }
}
