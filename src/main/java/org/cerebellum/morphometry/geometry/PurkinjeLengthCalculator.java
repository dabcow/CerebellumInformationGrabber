package org.cerebellum.morphometry.geometry;

import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Calibrated length of the Purkinje polyline, as a whole and per subsection.
 *
 * <h2>Per-subsection length</h2>
 * <p>A subsection's share of the Purkinje line is found by clipping the line against the
 * subsection's region: each segment is walked in sub-pixel steps and every step is credited to
 * the region containing its midpoint. Clipping at that resolution (rather than crediting each
 * whole segment to wherever its midpoint happens to fall) matters for lines traced with the
 * Segmented Line tool, whose segments are often tens of pixels long: a fissure crossing a long
 * segment near its middle would otherwise move the entire segment into one neighbour or, if the
 * midpoint fell exactly on the boundary, drop it from both.</p>
 *
 * <p>Clipping by containment rather than by arc-length range also handles a section that owns
 * two disjoint stretches of the line &mdash; which happens when the grey matter is a closed ring
 * and one section wraps around past the ends of the Purkinje line.</p>
 *
 * <h2>"Area" of a line</h2>
 * <p>This class also reports a Purkinje <em>area</em> ({@link #totalArea}), even though a
 * one-dimensional line doesn't have a meaningful area. It exists to match what selecting the
 * Purkinje ROI in the ROI Manager and clicking <em>Measure</em> would show in the Area column
 * &mdash; ImageJ's own Analyzer treats a line selection's "area" as the number of pixels its
 * path visits, times the calibrated area-per-pixel. Only the whole-line total is reported.</p>
 */
public final class PurkinjeLengthCalculator {

    /** Maximum step, in pixels, used when clipping the line against a region. */
    private static final double CLIP_STEP_PX = 0.25;

    private PurkinjeLengthCalculator() {
    }

    /**
     * Total calibrated length of the whole Purkinje polyline. Handles anisotropic pixel
     * calibration by scaling x and y separately before each segment's Euclidean length, the same
     * way {@code PolygonRoi#getLength()} calibrates a line.
     */
    public static double totalLength(PolygonRoi purkinje, ImagePlus imp) {
        Point2D.Double[] pts = GeometryUtils.extractPoints(purkinje);
        double[] scale = pixelScale(imp);
        double total = 0;
        for (int i = 0; i < pts.length - 1; i++) {
            total += calibratedDistance(pts[i], pts[i + 1], scale[0], scale[1]);
        }
        return total;
    }

    /** Calibrated length of the part of the Purkinje polyline that lies inside {@code region}. */
    public static double lengthInside(PolygonRoi purkinje, ImagePlus imp, Roi region) {
        if (region == null) {
            return 0.0;
        }
        double[] scale = pixelScale(imp);
        double total = 0;
        for (List<Point2D.Double> run : runsInside(purkinje, region)) {
            for (int i = 0; i < run.size() - 1; i++) {
                total += calibratedDistance(run.get(i), run.get(i + 1), scale[0], scale[1]);
            }
        }
        return total;
    }

    /**
     * The stretches of the Purkinje polyline lying inside {@code region}, each as a standalone
     * open-polyline {@link Roi} in absolute image coordinates &mdash; the geometry counterpart of
     * {@link #lengthInside}, for adding to the ROI Manager. Usually one stretch; two when the
     * region wraps around past the ends of the line. Empty if the line never enters the region.
     */
    public static List<PolygonRoi> segmentsInside(PolygonRoi purkinje, Roi region) {
        List<PolygonRoi> out = new ArrayList<>();
        if (region == null) {
            return out;
        }
        for (List<Point2D.Double> run : runsInside(purkinje, region)) {
            if (run.size() < 2) {
                continue;
            }
            float[] xs = new float[run.size()];
            float[] ys = new float[run.size()];
            for (int i = 0; i < run.size(); i++) {
                xs[i] = (float) run.get(i).x;
                ys[i] = (float) run.get(i).y;
            }
            out.add(new PolygonRoi(xs, ys, xs.length, Roi.POLYLINE));
        }
        return out;
    }

    /**
     * Clips the polyline against {@code region}, returning the maximal inside runs as point lists.
     * Each segment is subdivided into steps of at most {@link #CLIP_STEP_PX}; a step is inside if
     * the pixel containing its midpoint is inside the region. Original vertices are kept, so a
     * run follows the traced line exactly and only its two ends are interpolated.
     */
    private static List<List<Point2D.Double>> runsInside(PolygonRoi purkinje, Roi region) {
        Point2D.Double[] pts = GeometryUtils.extractPoints(purkinje);
        PixelMask inRegion = new PixelMask(region);
        List<List<Point2D.Double>> runs = new ArrayList<>();
        List<Point2D.Double> current = null;
        for (int i = 0; i < pts.length - 1; i++) {
            Point2D.Double a = pts[i];
            Point2D.Double b = pts[i + 1];
            int steps = Math.max(1, (int) Math.ceil(a.distance(b) / CLIP_STEP_PX));
            for (int s = 0; s < steps; s++) {
                double t0 = (double) s / steps;
                double t1 = (double) (s + 1) / steps;
                double tm = (t0 + t1) / 2;
                double mx = a.x + tm * (b.x - a.x);
                double my = a.y + tm * (b.y - a.y);
                boolean inside = inRegion.contains((int) Math.floor(mx), (int) Math.floor(my));
                if (inside) {
                    if (current == null) {
                        current = new ArrayList<>();
                        current.add(lerp(a, b, t0));
                        runs.add(current);
                    }
                    // Only materialise a point where the run ends or reaches an original vertex.
                    if (s == steps - 1) {
                        current.add(b);
                    }
                } else if (current != null) {
                    current.add(lerp(a, b, t0));
                    current = null;
                }
            }
        }
        return runs;
    }

    /**
     * Constant-time pixel membership test for an area {@link Roi}, using its rasterized mask
     * (the same pixels ImageJ measures). {@code Roi#contains} on a traced {@code ShapeRoi} walks
     * the whole outline on every call, which is far too slow for sub-pixel clipping.
     */
    private static final class PixelMask {
        private final int x0;
        private final int y0;
        private final int w;
        private final int h;
        private final byte[] pixels; // null means "every pixel of the bounds" (plain rectangle)

        PixelMask(Roi roi) {
            Rectangle b = roi.getBounds();
            ImageProcessor mask = roi.getMask();
            x0 = b.x;
            y0 = b.y;
            w = mask != null ? mask.getWidth() : b.width;
            h = mask != null ? mask.getHeight() : b.height;
            pixels = mask != null ? (byte[]) mask.getPixels() : null;
        }

        boolean contains(int x, int y) {
            int lx = x - x0;
            int ly = y - y0;
            if (lx < 0 || ly < 0 || lx >= w || ly >= h) {
                return false;
            }
            return pixels == null || pixels[ly * w + lx] != 0;
        }
    }

    private static Point2D.Double lerp(Point2D.Double a, Point2D.Double b, double t) {
        return new Point2D.Double(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y));
    }

    private static double[] pixelScale(ImagePlus imp) {
        Calibration cal = imp.getCalibration();
        return new double[] {
                cal != null ? cal.pixelWidth : 1.0,
                cal != null ? cal.pixelHeight : 1.0
        };
    }

    private static double calibratedDistance(Point2D.Double a, Point2D.Double b, double pixelWidth, double pixelHeight) {
        double dx = (b.x - a.x) * pixelWidth;
        double dy = (b.y - a.y) * pixelHeight;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * "Area" of the whole Purkinje polyline, exactly as ImageJ's own Analyze &gt; Measure would
     * report it for that ROI. See the class javadoc for what this number actually is.
     *
     * <p>Runs ImageJ's real measurement pipeline ({@link Analyzer}) on a copy of the line, which
     * requires temporarily making it the image's selection; whatever selection was there before
     * is always restored.</p>
     */
    public static double totalArea(PolygonRoi purkinje, ImagePlus imp) {
        if (purkinje == null) {
            return Double.NaN;
        }
        Roi previousRoi = imp.getRoi();
        try {
            imp.setRoi((Roi) purkinje.clone(), false);
            ResultsTable rt = new ResultsTable();
            new Analyzer(imp, Measurements.AREA, rt).measure();
            if (rt.size() == 0) {
                return Double.NaN;
            }
            return rt.getValue("Area", rt.size() - 1);
        } finally {
            if (previousRoi == null) {
                imp.deleteRoi();
            } else {
                imp.setRoi(previousRoi, false);
            }
        }
    }
}
