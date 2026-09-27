package org.cerebellum.morphometry.geometry;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.awt.*;

/**
 * Thin wrapper around {@link ShapeRoi}'s Boolean operations (AND / OR / NOT) and around
 * calibrated area measurement.
 *
 * <p><b>Why this class exists, beyond convenience:</b> {@code ShapeRoi.and/or/not/xor}
 * mutate the receiver in place and return {@code this} rather than producing a fresh
 * object. Every method here therefore takes a defensive {@code new ShapeRoi(...)} copy
 * before operating, so callers can safely reuse the same source {@code Roi} (e.g. the
 * whole-cerebellum outline) across many independent Boolean operations without one
 * operation corrupting the input for the next.</p>
 */
public final class BooleanROIProcessor {

    private BooleanROIProcessor() {
    }

    /** Defensive copy of any Roi as a fresh, independent ShapeRoi. */
    public static ShapeRoi copy(Roi r) {
        if (r == null) {
            return new ShapeRoi(new Polygon());
        }
        return new ShapeRoi(r);
    }

    /** Intersection: everything in both a and b. */
    public static ShapeRoi and(Roi a, Roi b) {
        return copy(a).and(copy(b));
    }

    /** Union: everything in a or b. */
    public static ShapeRoi or(Roi a, Roi b) {
        return copy(a).or(copy(b));
    }

    /** Difference: everything in a that is not in b. */
    public static ShapeRoi subtract(Roi a, Roi b) {
        return copy(a).not(copy(b));
    }

    /**
     * Calibrated area of an area-type Roi (zero for an empty/degenerate Roi). Uses the
     * image's mask-rasterization statistics rather than a polygon shoelace formula, so it
     * agrees with what "Analyze &gt; Measure" would report for the same Roi, and handles
     * composite/disjoint ShapeRoi shapes (with holes) correctly.
     */
    public static double area(Roi roi, ImagePlus imp) {
        if (roi == null) {
            return 0.0;
        }
        Rectangle bounds = roi.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            return 0.0;
        }
        ImageProcessor ip = imp.getProcessor();
        ip.setRoi(roi);
        ImageStatistics stats = ImageStatistics.getStatistics(ip, ij.measure.Measurements.AREA, imp.getCalibration());
        ip.resetRoi();
        return stats.area;
    }

    /**
     * True if a Roi covers no measurable area (used for containment checks such as
     * "is everything in A also in B", which holds iff {@code A NOT B} is empty).
     */
    public static boolean isEffectivelyEmpty(Roi roi, ImagePlus imp, double toleranceFraction, double referenceArea) {
        double a = area(roi, imp);
        double tolerance = Math.max(referenceArea * toleranceFraction, 1e-9);
        return a <= tolerance;
    }
}
