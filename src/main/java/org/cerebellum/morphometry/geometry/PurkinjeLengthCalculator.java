package org.cerebellum.morphometry.geometry;

import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Calibrated length of the Purkinje polyline, as a whole and per fissure-defined
 * subsection. Per-subsection length does not need any polygon/line clipping: each
 * partition's boundaries were built (in {@link FissurePartitioner}) to pass through
 * specific, known arc-length positions along this same polyline, so a subsection's
 * Purkinje length is simply the calibrated length of the polyline between those two
 * positions.
 *
 * <h2>"Area" of a line</h2>
 * <p>This class also reports a Purkinje <em>area</em> ({@link #totalArea}), even though
 * a one-dimensional line doesn't have a meaningful area. It exists to match what
 * selecting the Purkinje ROI in the ROI Manager and clicking <em>Measure</em> would show
 * in the Area column &mdash; ImageJ's own Analyzer treats a line selection's "area" as
 * the number of pixels its path visits, times the calibrated area-per-pixel. That's a
 * real, reproducible number, just not a geometrically meaningful one; it's included here
 * for parity with what a user would see measuring the ROI directly, not because it means
 * anything biologically. Only the whole-line total is reported (not a per-lobule
 * breakdown) since that's the only version of this number that's actually useful.</p>
 */
public final class PurkinjeLengthCalculator {

    private PurkinjeLengthCalculator() {
    }

    /** Total calibrated length of the whole Purkinje polyline. */
    public static double totalLength(PolygonRoi purkinje, ImagePlus imp) {
        return lengthBetween(purkinje, imp, 0.0, pixelArcLength(purkinje));
    }

    /** Total length of the polyline, measured in plain pixel-space arc length (uncalibrated). */
    public static double pixelArcLength(PolygonRoi purkinje) {
        Point2D.Double[] points = GeometryUtils.extractPoints(purkinje);
        double[] cumulative = GeometryUtils.cumulativeLengths(points);
        return cumulative[cumulative.length - 1];
    }

    /**
     * Calibrated length of the portion of the Purkinje polyline between two pixel-space
     * arc-length positions (as produced by {@link FissurePartitioner}, which works in
     * pixel space throughout). Handles anisotropic pixel calibration correctly by scaling
     * each x/y component independently before computing each sub-segment's Euclidean
     * length, the same way {@code ij.gui.PolygonRoi#getLength()} calibrates a whole line.
     */
    public static double lengthBetween(PolygonRoi purkinje, ImagePlus imp, double pixelArcStart, double pixelArcEnd) {
        Point2D.Double[] points = GeometryUtils.extractPoints(purkinje);
        double[] cumulative = GeometryUtils.cumulativeLengths(points);

        Calibration cal = imp.getCalibration();
        double pixelWidth = cal != null ? cal.pixelWidth : 1.0;
        double pixelHeight = cal != null ? cal.pixelHeight : 1.0;

        double total = 0.0;
        Point2D.Double prev = GeometryUtils.pointAtArcLength(points, cumulative, pixelArcStart);
        for (int i = 0; i < points.length; i++) {
            if (cumulative[i] <= pixelArcStart) {
                continue;
            }
            if (cumulative[i] >= pixelArcEnd) {
                break;
            }
            total += calibratedDistance(prev, points[i], pixelWidth, pixelHeight);
            prev = points[i];
        }
        Point2D.Double end = GeometryUtils.pointAtArcLength(points, cumulative, pixelArcEnd);
        total += calibratedDistance(prev, end, pixelWidth, pixelHeight);
        return total;
    }

    /**
     * The actual sub-polyline of the Purkinje line between two pixel-space arc-length
     * positions, as a standalone open-polyline {@link Roi} in the same absolute image
     * coordinates as the source. Companion to {@link #lengthBetween}, which measures this
     * same span without materializing it as a shape; use this when the geometry itself is
     * needed (e.g. to add it to the ROI Manager as a named per-lobule Purkinje segment).
     */
    public static PolygonRoi extractSubPolyline(PolygonRoi purkinje, double pixelArcStart, double pixelArcEnd) {
        Point2D.Double[] points = GeometryUtils.extractPoints(purkinje);
        double[] cumulative = GeometryUtils.cumulativeLengths(points);

        List<Point2D.Double> sub = new ArrayList<>();
        sub.add(GeometryUtils.pointAtArcLength(points, cumulative, pixelArcStart));
        for (int i = 0; i < points.length; i++) {
            if (cumulative[i] <= pixelArcStart) {
                continue;
            }
            if (cumulative[i] >= pixelArcEnd) {
                break;
            }
            sub.add(points[i]);
        }
        sub.add(GeometryUtils.pointAtArcLength(points, cumulative, pixelArcEnd));

        float[] xs = new float[sub.size()];
        float[] ys = new float[sub.size()];
        for (int i = 0; i < sub.size(); i++) {
            xs[i] = (float) sub.get(i).x;
            ys[i] = (float) sub.get(i).y;
        }
        return new PolygonRoi(xs, ys, xs.length, Roi.POLYLINE);
    }

    /**
     * Calibrated length of the part of the Purkinje polyline that lies inside {@code region}.
     *
     * <p>Used in preference to {@link #lengthBetween} for per-section lengths, because a
     * section's share of the Purkinje line isn't always a single contiguous arc range. When a
     * layer is a closed ring (the normal case — see {@link FissurePartitioner}), one section
     * wraps past the end of the Purkinje polyline and continues from its start, so it owns two
     * disjoint stretches of it. Asking "which bits of the line are inside this region" handles
     * that automatically, and needs no arc-range bookkeeping at all.</p>
     */
    public static double lengthInside(PolygonRoi purkinje, ImagePlus imp, Roi region) {
        if (region == null) {
            return 0.0;
        }
        Point2D.Double[] pts = GeometryUtils.extractPoints(purkinje);
        Calibration cal = imp.getCalibration();
        double pw = (cal != null) ? cal.pixelWidth : 1.0;
        double ph = (cal != null) ? cal.pixelHeight : 1.0;

        double total = 0;
        for (int i = 0; i < pts.length - 1; i++) {
            double mx = (pts[i].x + pts[i + 1].x) / 2.0;
            double my = (pts[i].y + pts[i + 1].y) / 2.0;
            if (region.contains((int) Math.round(mx), (int) Math.round(my))) {
                total += calibratedDistance(pts[i], pts[i + 1], pw, ph);
            }
        }
        return total;
    }

    /**
     * The stretch of the Purkinje polyline lying inside {@code region}, as a standalone open
     * polyline {@link Roi} in absolute image coordinates — the geometry counterpart of
     * {@link #lengthInside}, for adding to the ROI Manager. If the region owns more than one
     * disjoint stretch (see {@code lengthInside}), the longest is returned.
     */
    public static PolygonRoi extractInside(PolygonRoi purkinje, Roi region) {
        Point2D.Double[] pts = GeometryUtils.extractPoints(purkinje);

        List<List<Point2D.Double>> runs = new ArrayList<>();
        List<Point2D.Double> current = null;
        for (int i = 0; i < pts.length - 1; i++) {
            double mx = (pts[i].x + pts[i + 1].x) / 2.0;
            double my = (pts[i].y + pts[i + 1].y) / 2.0;
            boolean inside = region != null
                    && region.contains((int) Math.round(mx), (int) Math.round(my));
            if (inside) {
                if (current == null) {
                    current = new ArrayList<>();
                    current.add(pts[i]);
                    runs.add(current);
                }
                current.add(pts[i + 1]);
            } else {
                current = null;
            }
        }
        if (runs.isEmpty()) {
            return null;
        }

        List<Point2D.Double> longest = runs.get(0);
        double longestLen = pathLen(longest);
        for (List<Point2D.Double> run : runs) {
            double len = pathLen(run);
            if (len > longestLen) {
                longest = run;
                longestLen = len;
            }
        }

        float[] xs = new float[longest.size()];
        float[] ys = new float[longest.size()];
        for (int i = 0; i < longest.size(); i++) {
            xs[i] = (float) longest.get(i).x;
            ys[i] = (float) longest.get(i).y;
        }
        return new PolygonRoi(xs, ys, xs.length, Roi.POLYLINE);
    }

    private static double pathLen(List<Point2D.Double> pts) {
        double len = 0;
        for (int i = 1; i < pts.size(); i++) {
            len += pts.get(i - 1).distance(pts.get(i));
        }
        return len;
    }

    private static double calibratedDistance(Point2D.Double a, Point2D.Double b, double pixelWidth, double pixelHeight) {
        double dx = (b.x - a.x) * pixelWidth;
        double dy = (b.y - a.y) * pixelHeight;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * "Area" of the whole Purkinje polyline, exactly as ImageJ's own Analyze &gt; Measure
     * would report it for that ROI. See the class javadoc for what this number actually is.
     */
    public static double totalArea(PolygonRoi purkinje, ImagePlus imp) {
        return lineMeasureArea(purkinje, imp);
    }

    /**
     * Runs ImageJ's real measurement pipeline ({@link Analyzer}) on {@code lineRoi} and
     * returns whatever it reports in the Area column — reproducing exactly what a user
     * would see selecting this ROI in the ROI Manager and clicking Measure, rather than
     * computing anything independently. Temporarily sets {@code imp}'s active ROI to do
     * so (required by {@link Analyzer}, which reads from the image's current selection),
     * and always restores whatever selection was there before.
     */
    private static double lineMeasureArea(Roi lineRoi, ImagePlus imp) {
        if (lineRoi == null) {
            return Double.NaN;
        }
        Roi previousRoi = imp.getRoi();
        try {
            imp.setRoi(lineRoi);
            ResultsTable rt = new ResultsTable();
            Analyzer analyzer = new Analyzer(imp, Measurements.AREA, rt);
            analyzer.measure();
            if (rt.size() == 0) {
                return Double.NaN;
            }
            return rt.getValue("Area", rt.size() - 1);
        } finally {
            if (previousRoi == null) {
                imp.deleteRoi();
            } else {
                imp.setRoi(previousRoi);
            }
        }
    }
}
