package org.cerebellum.morphometry.testing;

import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatPolygon;

import java.util.ArrayList;
import java.util.List;

/**
 * A synthetic "phantom" cerebellar section with analytically known answers, used to test the
 * geometry pipeline end to end without real histology.
 *
 * <p>The layers are concentric circles around ({@link #CX}, {@link #CY}): the Cerebellum outline
 * (radius {@link #R_CEREBELLUM}), the Granular+WM outline ({@link #R_GRANULAR_WM}) and the White
 * Matter core ({@link #R_WHITE_MATTER}). The Purkinje line is an arc of radius {@link
 * #R_PURKINJE} &mdash; just outside Granular+WM, as in real tracings &mdash; covering the
 * "foliated" 300&deg; from {@link #PURKINJE_START_DEG} to {@link #PURKINJE_END_DEG} and leaving a
 * 60&deg; gap at the bottom of the image, which plays the part of the peduncle. Fissures are
 * radial lines, evenly spaced along the Purkinje arc.</p>
 *
 * <p>Angles are in image coordinates (y grows downwards), so 90&deg; points at the bottom of the
 * image. Between two radial fissures every layer is an exact annular sector, so interior
 * sections have closed-form areas and Purkinje lengths.</p>
 *
 * <p>With {@link Builder#pinched(boolean) pinched} set, White Matter and Granular+WM are extended
 * by a vertical "stalk" out through the Cerebellum outline at the peduncle &mdash; the README's
 * "closing the loop" technique &mdash; which turns the grey matter from a ring into an open
 * ribbon.</p>
 */
public final class SyntheticSection {

    public static final int WIDTH = 1200;
    public static final int HEIGHT = 1000;
    public static final double CX = 600;
    public static final double CY = 500;

    public static final double R_CEREBELLUM = 380;
    public static final double R_GRANULAR_WM = 270;
    public static final double R_WHITE_MATTER = 170;
    public static final double R_PURKINJE = 280;

    public static final double PURKINJE_START_DEG = 120;
    public static final double PURKINJE_END_DEG = 420;

    /** Half-width of the peduncle "stalk" used when {@link Builder#pinched(boolean)} is set. */
    public static final double STALK_HALF_WIDTH = 60;

    private static final int CIRCLE_VERTICES = 720;

    private final boolean pinched;
    private final int fissureCount;
    private final double purkinjeStepDeg;
    private final double fissureInnerRadius;
    private final double fissureOuterRadius;
    private final double pixelSize;
    private final String unit;

    private SyntheticSection(Builder b) {
        this.pinched = b.pinched;
        this.fissureCount = b.fissureCount;
        this.purkinjeStepDeg = b.purkinjeStepDeg;
        this.fissureInnerRadius = b.fissureInnerRadius;
        this.fissureOuterRadius = b.fissureOuterRadius;
        this.pixelSize = b.pixelSize;
        this.unit = b.unit;
    }

    public static Builder builder() {
        return new Builder();
    }

    // -----------------------------------------------------------------------
    // Inputs
    // -----------------------------------------------------------------------

    /** A blank image of the right size, calibrated as configured. Pixel content is never read. */
    public ImagePlus image() {
        ImagePlus imp = new ImagePlus("synthetic section", new ByteProcessor(WIDTH, HEIGHT));
        if (pixelSize != 1.0) {
            Calibration cal = new Calibration();
            cal.pixelWidth = pixelSize;
            cal.pixelHeight = pixelSize;
            cal.setUnit(unit);
            imp.setCalibration(cal);
        }
        return imp;
    }

    public Roi cerebellum() {
        return named(circle(R_CEREBELLUM), "CB");
    }

    public Roi granularWM() {
        Roi circle = circle(R_GRANULAR_WM);
        return named(pinched ? unionAsPolygon(circle, stalk()) : circle, "GL+WM");
    }

    public Roi whiteMatter() {
        Roi circle = circle(R_WHITE_MATTER);
        return named(pinched ? unionAsPolygon(circle, stalk()) : circle, "WM");
    }

    public PolygonRoi purkinje() {
        List<float[]> pts = new ArrayList<>();
        for (double deg = PURKINJE_START_DEG; deg < PURKINJE_END_DEG - 1e-9; deg += purkinjeStepDeg) {
            pts.add(polar(R_PURKINJE, deg));
        }
        pts.add(polar(R_PURKINJE, PURKINJE_END_DEG));
        return (PolygonRoi) named(polyline(pts), "PL");
    }

    /** Fissure angles in degrees, evenly spaced strictly inside the Purkinje arc. */
    public double[] fissureAnglesDeg() {
        double[] angles = new double[fissureCount];
        double span = PURKINJE_END_DEG - PURKINJE_START_DEG;
        for (int k = 0; k < fissureCount; k++) {
            angles[k] = PURKINJE_START_DEG + span * (k + 1) / (fissureCount + 1);
        }
        return angles;
    }

    public List<PolygonRoi> fissures() {
        List<PolygonRoi> result = new ArrayList<>();
        double[] angles = fissureAnglesDeg();
        for (int k = 0; k < angles.length; k++) {
            List<float[]> pts = new ArrayList<>();
            int segments = 8;
            for (int i = 0; i <= segments; i++) {
                double r = fissureOuterRadius + (fissureInnerRadius - fissureOuterRadius) * i / segments;
                pts.add(polar(r, angles[k]));
            }
            result.add((PolygonRoi) named(polyline(pts), "FL" + (k + 1)));
        }
        return result;
    }

    /** Every input ROI, named the way a user would name them in the ROI Manager. */
    public Roi[] rois() {
        List<Roi> all = new ArrayList<>();
        all.add(cerebellum());
        all.add(granularWM());
        all.add(whiteMatter());
        all.add(purkinje());
        all.addAll(fissures());
        return all.toArray(new Roi[0]);
    }

    // -----------------------------------------------------------------------
    // Closed-form expectations (calibrated units)
    // -----------------------------------------------------------------------

    /** Molecular-layer area of the annular sector between two angles. */
    public double molecularSectorArea(double fromDeg, double toDeg) {
        return sector(fromDeg, toDeg, R_CEREBELLUM, R_GRANULAR_WM);
    }

    /** Granular-layer area of the annular sector between two angles. */
    public double granularSectorArea(double fromDeg, double toDeg) {
        return sector(fromDeg, toDeg, R_GRANULAR_WM, R_WHITE_MATTER);
    }

    /**
     * Exact length of the traced Purkinje polyline between the rays at two angles. This is what a
     * perfect clip of the user's (chordal) polyline measures, which for a coarse trace is slightly
     * shorter than the ideal circle's arc.
     */
    public double purkinjeLength(double fromDeg, double toDeg) {
        List<Double> vertices = new ArrayList<>();
        for (double deg = PURKINJE_START_DEG; deg < PURKINJE_END_DEG - 1e-9; deg += purkinjeStepDeg) {
            vertices.add(deg);
        }
        vertices.add(PURKINJE_END_DEG);

        double total = 0;
        for (int i = 0; i < vertices.size() - 1; i++) {
            double a = vertices.get(i);
            double b = vertices.get(i + 1);
            double lo = Math.max(a, fromDeg);
            double hi = Math.min(b, toDeg);
            if (hi > lo) {
                total += chordPortion(a, b, lo, hi);
            }
        }
        return total * pixelSize;
    }

    public double totalPurkinjeLength() {
        return purkinjeLength(PURKINJE_START_DEG, PURKINJE_END_DEG);
    }

    public double pixelArea() {
        return pixelSize * pixelSize;
    }

    public boolean isPinched() {
        return pinched;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private double sector(double fromDeg, double toDeg, double rOuter, double rInner) {
        double theta = Math.toRadians(toDeg - fromDeg);
        return 0.5 * theta * (rOuter * rOuter - rInner * rInner) * pixelSize * pixelSize;
    }

    /** Length of the part of chord a→b (vertex angles, on the Purkinje circle) lying between rays lo and hi. */
    private static double chordPortion(double aDeg, double bDeg, double loDeg, double hiDeg) {
        float[] a = polar(R_PURKINJE, aDeg);
        float[] b = polar(R_PURKINJE, bDeg);
        double tLo = rayChordParameter(a, b, loDeg);
        double tHi = rayChordParameter(a, b, hiDeg);
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return Math.abs(tHi - tLo) * len;
    }

    /** Parameter t in [0,1] where the ray from the centre at {@code deg} crosses segment a→b. */
    private static double rayChordParameter(float[] a, float[] b, double deg) {
        double dx = Math.cos(Math.toRadians(deg));
        double dy = Math.sin(Math.toRadians(deg));
        double ax = a[0] - CX, ay = a[1] - CY;
        double ex = b[0] - a[0], ey = b[1] - a[1];
        // Solve ax + t*ex = s*dx, ay + t*ey = s*dy for t.
        double denom = ex * dy - ey * dx;
        if (Math.abs(denom) < 1e-12) {
            return 0;
        }
        return (ay * dx - ax * dy) / denom;
    }

    private static float[] polar(double r, double deg) {
        double rad = Math.toRadians(deg);
        return new float[] {(float) (CX + r * Math.cos(rad)), (float) (CY + r * Math.sin(rad))};
    }

    private static Roi circle(double r) {
        float[] xs = new float[CIRCLE_VERTICES];
        float[] ys = new float[CIRCLE_VERTICES];
        for (int i = 0; i < CIRCLE_VERTICES; i++) {
            float[] p = polar(r, 360.0 * i / CIRCLE_VERTICES);
            xs[i] = p[0];
            ys[i] = p[1];
        }
        return new PolygonRoi(xs, ys, CIRCLE_VERTICES, Roi.POLYGON);
    }

    /** A vertical bar from the centre down past the Cerebellum outline, through the peduncle gap. */
    private static Roi stalk() {
        float x0 = (float) (CX - STALK_HALF_WIDTH);
        float x1 = (float) (CX + STALK_HALF_WIDTH);
        float y0 = (float) CY;
        float y1 = (float) (CY + R_CEREBELLUM + 40);
        return new PolygonRoi(new float[] {x0, x1, x1, x0}, new float[] {y0, y0, y1, y1}, 4, Roi.POLYGON);
    }

    /** Union of two overlapping polygons, returned as the single closed polygon a user would trace. */
    private static Roi unionAsPolygon(Roi a, Roi b) {
        ShapeRoi union = new ShapeRoi(a).or(new ShapeRoi(b));
        FloatPolygon fp = union.getFloatPolygon();
        return new PolygonRoi(fp, Roi.POLYGON);
    }

    private static Roi polyline(List<float[]> pts) {
        float[] xs = new float[pts.size()];
        float[] ys = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i)[0];
            ys[i] = pts.get(i)[1];
        }
        return new PolygonRoi(xs, ys, xs.length, Roi.POLYLINE);
    }

    private static Roi named(Roi roi, String name) {
        roi.setName(name);
        return roi;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static final class Builder {
        private boolean pinched = true;
        private int fissureCount = 7;
        private double purkinjeStepDeg = 1.0;
        private double fissureInnerRadius = R_WHITE_MATTER - 6;
        private double fissureOuterRadius = R_CEREBELLUM + 6;
        private double pixelSize = 1.0;
        private String unit = "pixel";

        /** Extend WM and Granular+WM out through the peduncle (default {@code true}). */
        public Builder pinched(boolean pinched) {
            this.pinched = pinched;
            return this;
        }

        public Builder fissures(int count) {
            this.fissureCount = count;
            return this;
        }

        /** Angular spacing of the traced Purkinje vertices; large values mimic a coarse Segmented Line trace. */
        public Builder purkinjeStepDeg(double deg) {
            this.purkinjeStepDeg = deg;
            return this;
        }

        /** Radial extent of each traced fissure (defaults run from just outside the pia to just inside WM). */
        public Builder fissureRadii(double outer, double inner) {
            this.fissureOuterRadius = outer;
            this.fissureInnerRadius = inner;
            return this;
        }

        public Builder calibration(double pixelSize, String unit) {
            this.pixelSize = pixelSize;
            this.unit = unit;
            return this;
        }

        public SyntheticSection build() {
            return new SyntheticSection(this);
        }
    }
}
