package org.cerebellum.morphometry.geometry;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Measurements;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.awt.Polygon;
import java.awt.Rectangle;

/**
 * Thin wrapper around {@link ShapeRoi}'s Boolean operations (AND / NOT) and around
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

    /** Difference: everything in a that is not in b. */
    public static ShapeRoi subtract(Roi a, Roi b) {
        return copy(a).not(copy(b));
    }

    /**
     * Re-traces an area ROI from its pixel mask into a clean outline covering exactly the same
     * pixels (so its measured area is unchanged).
     *
     * <p>Shapes produced by the Boolean operations above can be split by Java2D into many
     * horizontal slabs (see {@link RasterSplitUtils}). That doesn't affect their measured area, but
     * when such a shape is drawn, every slab edge is stroked, which shows up as spurious horizontal
     * lines across the layer. Use this for anything a user will look at. Returns {@code null} for
     * an empty shape.</p>
     */
    public static Roi cleanOutline(Roi roi) {
        if (roi == null || !roi.isArea()) {
            return roi;
        }
        Rectangle bounds = roi.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        ImageProcessor mask = roi.getMask();
        if (mask == null) {
            return roi; // a plain rectangle is already a single clean outline
        }
        mask = mask.duplicate();
        mask.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
        Roi traced = new ThresholdToSelection().convert(mask);
        if (traced == null) {
            return null;
        }
        Rectangle tb = traced.getBounds();
        traced.setLocation(tb.x + bounds.x, tb.y + bounds.y);
        return traced;
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
        try {
            return ImageStatistics.getStatistics(ip, Measurements.AREA, imp.getCalibration()).area;
        } finally {
            ip.resetRoi();
        }
    }
}
