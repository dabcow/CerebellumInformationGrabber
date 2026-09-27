package org.cerebellum.morphometry.geometry;

import ij.gui.Roi;

import java.awt.*;
import java.awt.geom.Point2D;

/**
 * Vector-math and polyline utilities used by {@link FissurePartitioner} and
 * {@link PurkinjeLengthCalculator}. Everything here works in image/pixel coordinate
 * space (the same space Roi vertex coordinates are stored in) &mdash; calibration to
 * physical units is applied separately, only at the point of measurement.
 */
public final class GeometryUtils {

    private GeometryUtils() {
    }

    /** Extracts a Roi's vertices (works for polygons, polylines, and simple shapes alike). */
    public static Point2D.Double[] extractPoints(Roi roi) {
        var fp = roi.getFloatPolygon();
        Point2D.Double[] pts = new Point2D.Double[fp.npoints];
        for (int i = 0; i < fp.npoints; i++) {
            pts[i] = new Point2D.Double(fp.xpoints[i], fp.ypoints[i]);
        }
        return pts;
    }

    /** Cumulative path length at each vertex: result[0] == 0, result[n-1] == total length. */
    public static double[] cumulativeLengths(Point2D.Double[] points) {
        double[] cum = new double[points.length];
        for (int i = 1; i < points.length; i++) {
            cum[i] = cum[i - 1] + points[i - 1].distance(points[i]);
        }
        return cum;
    }

    /** Result of projecting a point onto a polyline: how far along it, and how close the match was. */
    public static final class Projection {
        public final double arcLength;
        public final double distance;

        public Projection(double arcLength, double distance) {
            this.arcLength = arcLength;
            this.distance = distance;
        }
    }

    /**
     * Projects {@code query} onto the polyline defined by {@code points}, returning both the
     * arc-length (using the precomputed {@code cumulative} lengths) and the distance of the
     * closest point on the polyline.
     */
    public static Projection project(Point2D.Double[] points, double[] cumulative, Point2D.Double query) {
        double bestDist = Double.POSITIVE_INFINITY;
        double bestArc = 0;
        for (int i = 0; i < points.length - 1; i++) {
            Point2D.Double a = points[i];
            Point2D.Double b = points[i + 1];
            double segDx = b.x - a.x;
            double segDy = b.y - a.y;
            double segLenSq = segDx * segDx + segDy * segDy;
            double t = segLenSq < 1e-12 ? 0.0
                    : ((query.x - a.x) * segDx + (query.y - a.y) * segDy) / segLenSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = a.x + t * segDx;
            double py = a.y + t * segDy;
            double dist = Point2D.distance(query.x, query.y, px, py);
            if (dist < bestDist) {
                bestDist = dist;
                bestArc = cumulative[i] + t * Math.sqrt(segLenSq);
            }
        }
        return new Projection(bestArc, bestDist);
    }

    public static Point2D.Double subtract(Point2D.Double a, Point2D.Double b) {
        return new Point2D.Double(a.x - b.x, a.y - b.y);
    }

    public static Point2D.Double add(Point2D.Double a, Point2D.Double b) {
        return new Point2D.Double(a.x + b.x, a.y + b.y);
    }

    public static Point2D.Double scale(Point2D.Double v, double s) {
        return new Point2D.Double(v.x * s, v.y * s);
    }

    public static double length(Point2D.Double v) {
        return Math.sqrt(v.x * v.x + v.y * v.y);
    }

    /** Unit vector in the direction of v; returns (1,0) for a zero/near-zero vector. */
    public static Point2D.Double normalize(Point2D.Double v) {
        double len = length(v);
        if (len < 1e-9) {
            return new Point2D.Double(1, 0);
        }
        return new Point2D.Double(v.x / len, v.y / len);
    }

    /** 90-degree counter-clockwise rotation (image coordinates, y grows downward). */
    public static Point2D.Double perpendicular(Point2D.Double v) {
        return new Point2D.Double(-v.y, v.x);
    }

    /**
     * Finds the point on a polygon's boundary closest to {@code query}.
     * When {@code closed} is true, the segment from the last vertex back to the first is
     * included (appropriate for a closed area ROI's outline, e.g. white matter).
     */
    public static Point2D.Double nearestPointOnPolygon(Point2D.Double[] polyPts, Point2D.Double query, boolean closed) {
        int n = polyPts.length;
        int segCount = closed ? n : n - 1;
        double bestDist = Double.POSITIVE_INFINITY;
        Point2D.Double best = polyPts[0];
        for (int i = 0; i < segCount; i++) {
            Point2D.Double a = polyPts[i];
            Point2D.Double b = polyPts[(i + 1) % n];
            double segDx = b.x - a.x;
            double segDy = b.y - a.y;
            double segLenSq = segDx * segDx + segDy * segDy;
            double t = segLenSq < 1e-12 ? 0.0
                    : ((query.x - a.x) * segDx + (query.y - a.y) * segDy) / segLenSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = a.x + t * segDx;
            double py = a.y + t * segDy;
            double dist = Point2D.distance(query.x, query.y, px, py);
            if (dist < bestDist) {
                bestDist = dist;
                best = new Point2D.Double(px, py);
            }
        }
        return best;
    }

    /** Polygon area via the shoelace formula (vertices in absolute pixel coordinates, implicitly closed). */
    public static double polygonArea(Point2D.Double[] pts) {
        int n = pts.length;
        if (n < 3) {
            return 0.0;
        }
        double area = 0.0;
        for (int i = 0; i < n; i++) {
            Point2D.Double a = pts[i];
            Point2D.Double b = pts[(i + 1) % n];
            area += a.x * b.y - b.x * a.y;
        }
        return Math.abs(area) / 2.0;
    }

    /** Arithmetic centroid of a Roi's polygon vertices (sufficient for direction-finding). */
    public static Point2D.Double centroid(Roi roi) {
        var fp = roi.getFloatPolygon();
        if (fp.npoints == 0) {
            return new Point2D.Double(0, 0);
        }
        double cx = 0, cy = 0;
        for (int i = 0; i < fp.npoints; i++) {
            cx += fp.xpoints[i];
            cy += fp.ypoints[i];
        }
        return new Point2D.Double(cx / fp.npoints, cy / fp.npoints);
    }

    /**
     * Builds a closed polygon strip of the given {@code halfWidth} around a polyline.
     * <p>Uses per-vertex perpendicular offsets (averaged at interior vertices) so the strip
     * correctly follows bends in a multi-segment line. The result can be passed directly to
     * {@code PolygonRoi(float[], float[], int, int)} for use in {@link ij.gui.ShapeRoi}
     * Boolean operations, which ensures the coordinates stay in the same absolute-pixel
     * system that all other ROIs use.</p>
     *
     * @param pts       polyline vertices in absolute pixel coordinates
     * @param halfWidth half the desired strip width in pixels
     * @return closed polygon vertices: left side forward, right side reversed
     */
    public static Point2D.Double[] polylineStrip(Point2D.Double[] pts, double halfWidth) {
        int n = pts.length;
        Point2D.Double[] left  = new Point2D.Double[n];
        Point2D.Double[] right = new Point2D.Double[n];
        for (int i = 0; i < n; i++) {
            Point2D.Double perp = vertexPerp(pts, i);
            left[i]  = add(pts[i], scale(perp,  halfWidth));
            right[i] = add(pts[i], scale(perp, -halfWidth));
        }
        Point2D.Double[] polygon = new Point2D.Double[2 * n];
        for (int i = 0; i < n; i++) {
            polygon[i]     = left[i];
            polygon[n + i] = right[n - 1 - i];
        }
        return polygon;
    }

    /**
     * Perpendicular direction at vertex {@code i} of a polyline.
     * At endpoints, perpendicular to the single adjacent segment.
     * At interior vertices, perpendicular to the average of the two adjacent segment directions
     * (bisector-like), which keeps the strip width constant around gentle bends.
     */
    private static Point2D.Double vertexPerp(Point2D.Double[] pts, int i) {
        int n = pts.length;
        if (n == 1) return new Point2D.Double(0, 1);
        if (i == 0)     return perpendicular(normalize(subtract(pts[1], pts[0])));
        if (i == n - 1) return perpendicular(normalize(subtract(pts[n - 1], pts[n - 2])));
        Point2D.Double d1  = normalize(subtract(pts[i],     pts[i - 1]));
        Point2D.Double d2  = normalize(subtract(pts[i + 1], pts[i]));
        Point2D.Double avg = add(d1, d2);
        double len = length(avg);
        return len < 1e-9 ? perpendicular(d1) : perpendicular(normalize(avg));
    }

    /** Interpolates the point at the given arc-length distance along a polyline (clamped to the ends). */
    public static Point2D.Double pointAtArcLength(Point2D.Double[] points, double[] cumulative, double arc) {
        int n = points.length;
        if (arc <= 0)                  return points[0];
        if (arc >= cumulative[n - 1])  return points[n - 1];
        for (int i = 0; i < n - 1; i++) {
            if (cumulative[i + 1] >= arc) {
                double segLen = cumulative[i + 1] - cumulative[i];
                double t = segLen < 1e-9 ? 0 : (arc - cumulative[i]) / segLen;
                double x = points[i].x + t * (points[i + 1].x - points[i].x);
                double y = points[i].y + t * (points[i + 1].y - points[i].y);
                return new Point2D.Double(x, y);
            }
        }
        return points[n - 1];
    }

    public static double diagonal(Rectangle bounds) {
        return Math.sqrt((double) bounds.width * bounds.width + (double) bounds.height * bounds.height);
    }
}

