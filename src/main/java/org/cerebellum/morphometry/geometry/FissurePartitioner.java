package org.cerebellum.morphometry.geometry;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.PartitionSet;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Partitions the cerebellum into lobule regions from {@code N} traced fissures (standard case:
 * 7 fissures &rarr; 8 lobules, but any count is supported &mdash; see {@link
 * #labelsForSubsectionCount}) using a <b>subtract-and-split</b> strategy (equivalent to the ROI
 * Manager's XOR&thinsp;+&thinsp;Split), applied <b>once to the whole Grey Matter ring</b> (each
 * lobule's molecular and granular parts are derived from the same piece downstream by {@link
 * PartitionClipper}), with an <b>adaptive strip-width search</b> to make the split robust.
 *
 * <h2>Things that went wrong before landing on this design</h2>
 * <ol>
 *   <li><b>Half-planes.</b> Needing to know which side of a cut is "toward the last lobule"
 *       was estimated with a cross-product sign test, which is unreliable exactly where
 *       several cuts cross close together.</li>
 *   <li><b>Extending cuts across the solid Cerebellum outline.</b> The Cerebellum ROI has
 *       no White-Matter-shaped hole, so a cut that only reaches the granular layer leaves
 *       everything connected underneath through the un-cut white matter core. Extending
 *       cuts all the way through that core "to be safe" instead let a straight line
 *       re-emerge through the pial surface at some unrelated point, carving a second,
 *       unwanted cut through neighbouring tissue.</li>
 *   <li><b>One fixed strip width, in vector geometry.</b> Java2D's {@code Area} silently
 *       fragmented cuts into spurious extra pieces when the strip was too thin relative to the
 *       local vertex spacing, while a strip wide enough to be safe in one region merged
 *       neighbouring cuts in another. Splitting now happens on a pixel mask (see {@link
 *       RasterSplitUtils}) with a small set of candidate widths.</li>
 *   <li><b>Assuming the traced fissure already spans the tissue.</b> Where a fissure stops is
 *       up to whoever traced it. Each fissure is therefore <em>extended</em> along its own
 *       end-tangents until it provably spans the tissue (pial end just outside the Cerebellum,
 *       deep end inside White Matter), which is exactly what a partition boundary has to do.</li>
 *   <li><b>Splitting Molecular and Granular separately and pairing the pieces.</b> The two layers
 *       fragment into different arrangements, so pairing piece <i>i</i> of one with piece
 *       <i>i</i> of the other fused unrelated lobules. Partitioning one shape removes the pairing.</li>
 *   <li><b>Discarding the cutting strips.</b> The strips subtracted to separate the pieces were
 *       never given back, so every lobule lost a band of tissue along each bounding fissure
 *       (about 4&ndash;6% of its area on a synthetic test section) and its share of the Purkinje
 *       line inside that band. Strip pixels are now re-assigned to the nearest piece, which puts
 *       each boundary on the fissure itself; see {@link RasterSplitUtils#completePartition}.</li>
 * </ol>
 *
 * <h2>This design</h2>
 * <ol>
 *   <li><b>Orient</b> each fissure pial&rarr;white-matter by the anatomical <b>nesting depth</b>
 *       of its endpoints (inside White Matter &gt; inside Granular+WM &gt; inside Cerebellum
 *       &gt; outside), which is robust to how the tissue folds; ties fall back to proximity to
 *       the White Matter, then Cerebellum, outline.</li>
 *   <li><b>Build one spanning cut per fissure</b> by keeping the user's traced vertices and
 *       extending the two ends along their own tangents until the cut runs from just outside the
 *       Cerebellum to inside White Matter. Extension stops the instant its target is reached,
 *       is distance-capped, and leaves an end as-traced if its target is unreachable.</li>
 *   <li><b>Partition the Grey Matter</b> ({@code Cerebellum \ WhiteMatter}) once with those cuts:
 *       rasterize it, subtract thin strips around the cuts, label the connected pieces. How many
 *       pieces to expect follows from the mask's topology (see {@link #partitionGrey}). Strip
 *       widths are tried thinnest first.</li>
 *   <li><b>Complete the partition</b>: give the strip pixels and any slivers back to the
 *       neighbouring pieces, so the lobules tile the grey matter with no gaps.</li>
 *   <li><b>Order and name</b> the pieces by where each one's own stretch of the Purkinje line
 *       lies (median arc-length). The standard names are only used when there are exactly eight
 *       pieces <em>and</em> the first and last lobules are actually separated.</li>
 * </ol>
 */
public final class FissurePartitioner {

    /**
     * Anatomical subsection labels for the standard rodent vermis scheme (2Cb nearest the
     * Purkinje start). Lobule 1 is conventionally fused with 2 and not measured separately.
     */
    private static final String[] STANDARD_LOBULE_LABELS = {
            "2Cb", "3Cb", "4/5Cb", "6Cb", "7Cb", "8Cb", "9Cb", "10Cb"
    };

    /** Number of sections that receive the standard lobule names. */
    public static final int STANDARD_SECTION_COUNT = STANDARD_LOBULE_LABELS.length;

    /**
     * Candidate strip half-widths to try, each expressed as a fraction of the average cut
     * length. Tried thinnest-first; the first one that yields the expected number of pieces
     * covering most of the grey matter is used.
     */
    private static final double[] WIDTH_FRACTION_CANDIDATES = {0.02, 0.06, 0.15};
    private static final double MIN_ABS_HALF_WIDTH_PX = 1.0;

    /** A width candidate is accepted only if the surviving pieces retain at least this much of the area. */
    private static final double MIN_ACCEPTABLE_COVERAGE = 0.75;

    /**
     * Pieces smaller than this fraction of the grey matter are treated as slivers (e.g. the corner
     * a straight-line fissure shaves off a folded band) and merged into a neighbour, rather than
     * counted as lobules. Well below any genuine lobule, which is typically many percent.
     */
    private static final double MIN_PIECE_AREA_FRACTION = 0.015;

    /** Distance a spanning-cut end is grown per iteration while searching for its target region. */
    private static final double EXTEND_STEP_PX = 2.0;

    private FissurePartitioner() {
    }

    /**
     * Labels for {@code count} subsections, however they arose &mdash; from fissures within one
     * traced piece, or (see {@link org.cerebellum.morphometry.measurement.MeasurementEngine#combine})
     * from several separately-traced pieces of one cerebellum pooled together. Uses the standard
     * rodent vermis names when the count matches that scheme exactly, and generic "Section N"
     * names otherwise, since a specific anatomical name would then be a guess.
     */
    public static String[] labelsForSubsectionCount(int count) {
        if (count == STANDARD_LOBULE_LABELS.length) {
            return STANDARD_LOBULE_LABELS.clone();
        }
        return genericLabels(count);
    }

    /** "Section 1", "Section 2", &hellip; for when a specific anatomical name would be a guess. */
    public static String[] genericLabels(int count) {
        String[] labels = new String[count];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "Section " + (i + 1);
        }
        return labels;
    }

    public static PartitionSet partition(LayerSet layers, Diagnostics diag) {
        List<PolygonRoi> rawFissures = layers.getFissures();
        if (rawFissures.isEmpty()) {
            throw new IllegalStateException(
                    "FissurePartitioner requires at least 1 fissure (validated upstream); got 0");
        }

        Roi cerebellumRoi  = layers.getCerebellum();
        Roi granularWMRoi  = layers.getGranularWM();
        Roi whiteMatterRoi = layers.getWhiteMatter();
        if (whiteMatterRoi == null) {
            throw new IllegalStateException("FissurePartitioner requires a White Matter ROI (validated upstream)");
        }

        Point2D.Double[] purkPoints     = GeometryUtils.extractPoints(layers.getPurkinje());
        double[]         purkCumulative = GeometryUtils.cumulativeLengths(purkPoints);
        Point2D.Double[] cerebellumBoundary  = GeometryUtils.extractPoints(cerebellumRoi);
        Point2D.Double[] whiteMatterBoundary = GeometryUtils.extractPoints(whiteMatterRoi);

        // -----------------------------------------------------------------------
        // Orient each fissure OUTER(pial)→INNER(white-matter) by the anatomical NESTING DEPTH
        // of its two endpoints.
        //
        // Depth, not raw distance to the Cerebellum outline, decides which end is pial. In a
        // tightly folded vermis the deep (white-matter) end of a fissure often sits closer to a
        // NEIGHBOURING folium's pial surface than the fissure's own mouth does, so a
        // nearest-outline test silently flips such fissures. Ties (e.g. both ends still in the
        // molecular layer for a short trace) fall back to proximity to the White Matter outline
        // (inner end is nearer it), then to the Cerebellum outline.
        // -----------------------------------------------------------------------
        List<OrientedFissure> oriented = new ArrayList<>(rawFissures.size());
        for (PolygonRoi fissure : rawFissures) {
            Point2D.Double[] raw = GeometryUtils.extractPoints(fissure);
            Point2D.Double a = raw[0], b = raw[raw.length - 1];
            int depthA = nestingDepth(a, cerebellumRoi, granularWMRoi, whiteMatterRoi);
            int depthB = nestingDepth(b, cerebellumRoi, granularWMRoi, whiteMatterRoi);
            boolean aIsPial;
            if (depthA != depthB) {
                aIsPial = depthA < depthB; // shallower endpoint is the pial end
            } else {
                double aWM = GeometryUtils.nearestPointOnPolygon(whiteMatterBoundary, a, true).distance(a);
                double bWM = GeometryUtils.nearestPointOnPolygon(whiteMatterBoundary, b, true).distance(b);
                if (Math.abs(aWM - bWM) > 1e-6) {
                    aIsPial = aWM > bWM; // farther from white matter is the pial end
                } else {
                    double aCe = GeometryUtils.nearestPointOnPolygon(cerebellumBoundary, a, true).distance(a);
                    double bCe = GeometryUtils.nearestPointOnPolygon(cerebellumBoundary, b, true).distance(b);
                    aIsPial = aCe <= bCe; // nearer the outline is the pial end
                }
            }
            Point2D.Double[] pts = aIsPial ? raw : reversed(raw);
            oriented.add(new OrientedFissure(pts, purkinjeCrossingArc(pts, purkPoints, purkCumulative)));
        }
        oriented.sort(Comparator.comparingDouble(f -> f.purkinjeArc));
        int n = oriented.size();

        // -----------------------------------------------------------------------
        // Build ONE spanning cut per fissure. A ring is only severed by a curve that crosses it
        // from OUTSIDE its outer edge to INSIDE its inner edge, and where a trace stops is up to
        // whoever drew it, so each fissure is EXTENDED along its own end-tangents until it spans
        // the tissue: the pial end pushed just outside the Cerebellum outline, the deep end pushed
        // until it is inside White Matter. The middle keeps the user's traced shape exactly.
        // -----------------------------------------------------------------------
        double diagonal = GeometryUtils.diagonal(cerebellumRoi.getBounds());
        List<Point2D.Double[]> spanningCuts = new ArrayList<>(n);
        List<PolygonRoi>       overlayLines = new ArrayList<>(n);
        for (OrientedFissure f : oriented) {
            Point2D.Double[] cut = spanningCut(f.points, cerebellumRoi, whiteMatterRoi, diagonal);
            spanningCuts.add(cut);
            overlayLines.add(toPolygonRoi(cut, Roi.POLYLINE));
        }

        // -----------------------------------------------------------------------
        // Partition ONE shape — the Grey Matter ({@code Cerebellum \ WhiteMatter}) — into
        // per-lobule footprints; PartitionClipper derives each lobule's molecular and granular
        // parts downstream by intersecting that footprint with the whole-layer shapes.
        // -----------------------------------------------------------------------
        ShapeRoi greyBase = BooleanROIProcessor.subtract(cerebellumRoi, whiteMatterRoi);
        GreySplit split = partitionGrey(greyBase, spanningCuts, diag);
        List<RasterSplitUtils.Component> comps = split.labeling.components;

        // -----------------------------------------------------------------------
        // Order the pieces along the Purkinje line, by where each piece's OWN stretch of the line
        // lies (median arc-length of the Purkinje samples that fall inside it). This is more
        // robust than projecting each piece's centroid onto the line: a curved or branched lobule's
        // centroid can sit nearer a neighbour's stretch of the Purkinje line than its own.
        // Pieces the Purkinje line never enters fall back to centroid projection.
        // -----------------------------------------------------------------------
        PurkinjeSamples samples = samplePurkinje(purkPoints, purkCumulative, split);
        List<Piece> pieces = new ArrayList<>(comps.size());
        for (int c = 0; c < comps.size(); c++) {
            RasterSplitUtils.Component comp = comps.get(c);
            if (comp.size() == 0) {
                continue;
            }
            double orderArc = samples.medianArc(c);
            if (Double.isNaN(orderArc)) {
                Point2D.Double centroid = new Point2D.Double(
                        comp.centroidX() + split.canvas.x + 0.5, comp.centroidY() + split.canvas.y + 0.5);
                orderArc = GeometryUtils.project(purkPoints, purkCumulative, centroid).arcLength;
            }
            int inside = RasterSplitUtils.interiorPixel(comp, split.canvas.width);
            Point2D.Double anchor = new Point2D.Double(
                    inside % split.canvas.width + split.canvas.x + 0.5,
                    inside / split.canvas.width + split.canvas.y + 0.5);
            Roi traced = RasterSplitUtils.traceComponent(comp, split.canvas.width, split.canvas);
            pieces.add(new Piece(new ShapeRoi(traced), orderArc, anchor));
        }
        pieces.sort(Comparator.comparingDouble(p -> p.orderArc));

        // The first and last lobules are "joined" when the piece holding the start of the Purkinje
        // line also holds its end — the normal outcome for an unpinched ring, where one piece wraps
        // around through the peduncle. Standard names would then put "2Cb" on tissue that is
        // really 2Cb + 10Cb, so they are withheld.
        boolean endsJoined = pieces.size() > 1 && samples.firstLabel >= 0 && samples.firstLabel == samples.lastLabel;
        int count = pieces.size();
        String[] labels;
        if (endsJoined) {
            boolean standardWithheld = count == STANDARD_LOBULE_LABELS.length;
            labels = standardWithheld ? genericLabels(count) : labelsForSubsectionCount(count);
            diag.warn("The first and last lobules are joined into one section (they are still connected "
                    + "around the base of the cerebellum), so one section is missing"
                    + (standardWithheld ? " and the standard lobule names (2Cb … 10Cb) would be wrong, so generic "
                            + "\"Section N\" names are used instead" : "")
                    + ". Add a fissure line across the base of the cerebellum between the first and last "
                    + "lobules, or extend White Matter out to the Cerebellum outline there "
                    + "(README: \"Closing the loop\").");
        } else {
            labels = labelsForSubsectionCount(count);
        }

        // Sections are numbered from the Purkinje line's first traced point, so the direction it
        // was traced in decides the numbering. The convention is clockwise (which runs from
        // lobule 2 over the top to lobule 10 when rostral is on the left); a counterclockwise
        // trace numbers everything in reverse, which is easy to miss in the table.
        if (count > 1) {
            double[] c = whiteMatterRoi.getContourCentroid();
            double sweep = sweepDegrees(purkPoints, c[0], c[1]);
            if (sweep < -COUNTERCLOCKWISE_THRESHOLD_DEG) {
                diag.warn(String.format(Locale.ROOT, "The Purkinje line was traced counterclockwise (it turns %.0f° "
                        + "counterclockwise around the white matter). Sections are numbered from the line's first "
                        + "point, so they are numbered in reverse compared with a clockwise trace. If the labels "
                        + "look reversed, retrace the Purkinje line clockwise.", -sweep));
            }
        }

        List<PartitionSet.Partition> partitions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Piece p = pieces.get(i);
            partitions.add(new PartitionSet.Partition(labels[i], p.shape, p.anchor));
        }
        return new PartitionSet(partitions, overlayLines, split.ring, endsJoined);
    }

    /** A net counterclockwise turn larger than this (degrees) triggers the direction warning. */
    private static final double COUNTERCLOCKWISE_THRESHOLD_DEG = 90;

    /**
     * Net angle, in degrees, that the polyline turns around ({@code cx}, {@code cy}). Positive
     * means clockwise as seen on screen (image y points down, so a clockwise path has increasing
     * {@code atan2} angles). Folds in and out of lobules cancel out; only the overall direction
     * around the white matter remains.
     */
    static double sweepDegrees(Point2D.Double[] pts, double cx, double cy) {
        double total = 0;
        double prev = Math.atan2(pts[0].y - cy, pts[0].x - cx);
        for (int i = 1; i < pts.length; i++) {
            double a = Math.atan2(pts[i].y - cy, pts[i].x - cx);
            double d = a - prev;
            if (d > Math.PI) {
                d -= 2 * Math.PI;
            } else if (d < -Math.PI) {
                d += 2 * Math.PI;
            }
            total += d;
            prev = a;
        }
        return Math.toDegrees(total);
    }

    // -----------------------------------------------------------------------
    // Core: subtract-and-split the grey matter with an adaptive strip-width search.
    // -----------------------------------------------------------------------

    /** The outcome of splitting the grey matter: a labelled canvas-sized mask and its topology. */
    private static final class GreySplit {
        final RasterSplitUtils.Labeling labeling;
        final Rectangle canvas;
        final boolean ring;

        GreySplit(RasterSplitUtils.Labeling labeling, Rectangle canvas, boolean ring) {
            this.labeling = labeling;
            this.canvas = canvas;
            this.ring = ring;
        }
    }

    /**
     * Subtracts thin strips built around {@code cuts} from the rasterized grey matter and labels
     * the resulting pieces, trying increasingly wide strips until one yields the expected number
     * of pieces covering most of the grey matter. The winning split is then completed (strip
     * pixels and slivers handed back to their neighbours) so the pieces tile the grey matter.
     *
     * <p><b>How many pieces to expect depends on the shape's topology.</b> An open ribbon is
     * cut into {@code N+1} pieces by {@code N} cuts, but a closed ring (an annulus &mdash; a shape
     * with a hole in it) is cut into only {@code N}: the first cut merely opens the ring into
     * a ribbon without separating anything. Grey matter is normally a ring, since it wraps all
     * the way around the white matter core; it's only a ribbon when the ring has been
     * deliberately pinched open at the peduncle (see the README's "Closing the loop"). This is
     * detected from the mask itself rather than assumed.</p>
     */
    private static GreySplit partitionGrey(ShapeRoi greyBase, List<Point2D.Double[]> cuts, Diagnostics diag) {
        Rectangle bounds = greyBase.getBounds();
        int margin = (int) Math.max(50, 0.08 * Math.max(bounds.width, bounds.height));
        Rectangle canvas = new Rectangle(bounds.x - margin, bounds.y - margin,
                bounds.width + 2 * margin, bounds.height + 2 * margin);
        int w = canvas.width, h = canvas.height;

        boolean[] baseMask = RasterSplitUtils.rasterize(greyBase, canvas);
        long baseArea = RasterSplitUtils.countTrue(baseMask);
        long minPieceArea = (long) (baseArea * MIN_PIECE_AREA_FRACTION);

        boolean ring = RasterSplitUtils.hasHole(baseMask, w, h);
        int expected = cuts.size() + (ring ? 0 : 1);
        if (ring) {
            diag.note("The grey matter is a closed ring, so " + cuts.size() + " fissure line(s) split it into "
                    + expected + " section(s).");
        } else {
            diag.note("The grey matter is an open ribbon (pinched at the peduncle), so " + cuts.size()
                    + " fissure(s) split it into " + expected + " section(s).");
        }

        double avgLen = 0;
        for (Point2D.Double[] c : cuts) {
            avgLen += pathLength(c);
        }
        avgLen /= cuts.size();

        RasterSplitUtils.Labeling best = null;
        int bestKept = 0;
        long bestArea = -1;
        int bestDiff = Integer.MAX_VALUE;

        for (double frac : WIDTH_FRACTION_CANDIDATES) {
            double halfWidth = Math.max(MIN_ABS_HALF_WIDTH_PX, avgLen * frac);

            boolean[] divided = baseMask.clone();
            boolean[] stripsMask = new boolean[w * h];
            for (Point2D.Double[] c : cuts) {
                Point2D.Double[] stripPts = GeometryUtils.polylineStrip(c, halfWidth);
                RasterSplitUtils.rasterizeInto(stripsMask, toPolygonRoi(stripPts, Roi.POLYGON), canvas);
            }
            RasterSplitUtils.subtractInPlace(divided, stripsMask);

            RasterSplitUtils.Labeling raw = RasterSplitUtils.connectedComponents(divided, w, h);
            int kept = 0;
            long keptArea = 0;
            for (RasterSplitUtils.Component c : raw.components) {
                if (c.size() >= minPieceArea) {
                    kept++;
                    keptArea += c.size();
                }
            }

            if (kept == expected && keptArea >= baseArea * MIN_ACCEPTABLE_COVERAGE) {
                return new GreySplit(complete(raw, baseMask, w, h, minPieceArea, baseArea, diag), canvas, ring);
            }

            int diff = Math.abs(kept - expected);
            if (diff < bestDiff || (diff == bestDiff && keptArea > bestArea)) {
                bestDiff = diff;
                bestArea = keptArea;
                bestKept = kept;
                best = raw;
            }
        }

        diag.warn("Could not split the grey matter cleanly into " + expected + " sections after trying "
                + WIDTH_FRACTION_CANDIDATES.length + " cut widths; using the closest attempt (" + bestKept
                + " sections). Usually one fissure does not cross the whole grey matter — it stops short of "
                + "the pial surface or White Matter, or cuts across a fold instead of along it. Check the "
                + "white fissure lines in the overlay.");
        return new GreySplit(complete(best, baseMask, w, h, minPieceArea, baseArea, diag), canvas, ring);
    }

    private static RasterSplitUtils.Labeling complete(RasterSplitUtils.Labeling raw, boolean[] baseMask,
            int w, int h, long minPieceArea, long baseArea, Diagnostics diag) {
        RasterSplitUtils.Labeling done = RasterSplitUtils.completePartition(raw, baseMask, w, h, minPieceArea);
        long assigned = done.labelledArea();
        if (baseArea > 0 && assigned < baseArea * 0.995) {
            diag.note(String.format(Locale.ROOT,
                    "%.1f%% of the grey matter could not be assigned to any section (isolated fragments).",
                    100.0 * (baseArea - assigned) / baseArea));
        }
        return done;
    }

    // -----------------------------------------------------------------------
    // Purkinje sampling (for ordering and end-joining detection)
    // -----------------------------------------------------------------------

    /** Arc-length positions of Purkinje-line samples, grouped by the piece they fall in. */
    private static final class PurkinjeSamples {
        final double[][] arcsByLabel;
        final int firstLabel;
        final int lastLabel;

        PurkinjeSamples(double[][] arcsByLabel, int firstLabel, int lastLabel) {
            this.arcsByLabel = arcsByLabel;
            this.firstLabel = firstLabel;
            this.lastLabel = lastLabel;
        }

        double medianArc(int label) {
            double[] arcs = arcsByLabel[label];
            if (arcs.length == 0) {
                return Double.NaN;
            }
            return arcs[arcs.length / 2]; // samples are generated in increasing arc order
        }
    }

    /** Samples the Purkinje polyline at &le;1 px spacing and records which piece each sample lies in. */
    private static PurkinjeSamples samplePurkinje(Point2D.Double[] pts, double[] cum, GreySplit split) {
        int count = split.labeling.components.size();
        double[][] tmp = new double[count][];
        int[] sizes = new int[count];
        for (int c = 0; c < count; c++) {
            tmp[c] = new double[16];
        }
        double total = cum[cum.length - 1];
        int steps = Math.max(1, (int) Math.ceil(total));
        int first = -1, last = -1;
        Rectangle canvas = split.canvas;
        for (int i = 0; i <= steps; i++) {
            double arc = total * i / steps;
            Point2D.Double p = GeometryUtils.pointAtArcLength(pts, cum, arc);
            int x = (int) Math.floor(p.x) - canvas.x;
            int y = (int) Math.floor(p.y) - canvas.y;
            if (x < 0 || y < 0 || x >= canvas.width || y >= canvas.height) {
                continue;
            }
            int label = split.labeling.labels[y * canvas.width + x];
            if (label < 0) {
                continue;
            }
            if (first < 0) {
                first = label;
            }
            last = label;
            if (sizes[label] == tmp[label].length) {
                tmp[label] = Arrays.copyOf(tmp[label], tmp[label].length * 2);
            }
            tmp[label][sizes[label]++] = arc;
        }
        double[][] arcs = new double[count][];
        for (int c = 0; c < count; c++) {
            arcs[c] = Arrays.copyOf(tmp[c], sizes[c]);
        }
        return new PurkinjeSamples(arcs, first, last);
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    private static final class OrientedFissure {
        final Point2D.Double[] points; // index 0 = pial end, last = WM end
        final double purkinjeArc;      // where this fissure crosses the Purkinje line

        OrientedFissure(Point2D.Double[] points, double purkinjeArc) {
            this.points = points;
            this.purkinjeArc = purkinjeArc;
        }
    }

    private static final class Piece {
        final ShapeRoi shape;
        final double orderArc;
        final Point2D.Double anchor;

        Piece(ShapeRoi shape, double orderArc, Point2D.Double anchor) {
            this.shape = shape;
            this.orderArc = orderArc;
            this.anchor = anchor;
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Anatomical nesting depth of a point: 3 inside White Matter, 2 inside Granular+WM, 1 inside
     * the Cerebellum, 0 outside it.
     */
    private static int nestingDepth(Point2D.Double p, Roi cerebellum, Roi granularWM, Roi whiteMatter) {
        if (contains(whiteMatter, p)) return 3;
        if (contains(granularWM, p))  return 2;
        if (contains(cerebellum, p))  return 1;
        return 0;
    }

    /** Null-safe test of whether the pixel containing {@code p} is inside {@code roi}. */
    private static boolean contains(Roi roi, Point2D.Double p) {
        return roi != null && roi.contains((int) Math.floor(p.x), (int) Math.floor(p.y));
    }

    /**
     * Turns an oriented (pial&rarr;white-matter) fissure into a cut that spans the whole tissue:
     * its pial end is grown outward until it lies just outside the Cerebellum, and its deep end
     * is grown inward until it lies inside White Matter.
     */
    private static Point2D.Double[] spanningCut(Point2D.Double[] oriented, Roi cerebellum,
            Roi whiteMatter, double diagonal) {
        double capOuter = extensionCap(oriented[0], cerebellum, diagonal);
        Point2D.Double[] withPial = extendEndUntil(oriented, true, p -> !contains(cerebellum, p), capOuter);

        int last = withPial.length - 1;
        double capInner = extensionCap(withPial[last], whiteMatter, diagonal);
        return extendEndUntil(withPial, false, p -> contains(whiteMatter, p), capInner);
    }

    /**
     * How far {@code extendEndUntil} may grow an end before giving up: scales with the tip's
     * distance to the target outline, floored so a tip already on the boundary can still step
     * across, and hard-capped at a fraction of the section diagonal so no cut can run away.
     */
    private static double extensionCap(Point2D.Double tip, Roi target, double diagonal) {
        if (target == null) {
            return 0.0;
        }
        double dist = GeometryUtils.nearestPointOnPolygon(GeometryUtils.extractPoints(target), tip, true)
                .distance(tip);
        return Math.min(0.30 * diagonal, Math.max(15.0, 3.0 * dist));
    }

    /**
     * Grows one end of {@code path} outward along its own end-tangent, in {@link #EXTEND_STEP_PX}
     * steps, until {@code target} accepts the new tip, and adds the reached point to the path.
     * Returns {@code path} unchanged if the tip already satisfies {@code target} or if it is not
     * satisfied within {@code maxExtend} &mdash; never a blind straight shot across the section.
     */
    private static Point2D.Double[] extendEndUntil(Point2D.Double[] path, boolean atStart,
            Predicate<Point2D.Double> target, double maxExtend) {
        if (path.length < 2 || maxExtend <= 0) {
            return path;
        }
        Point2D.Double tip = atStart ? path[0] : path[path.length - 1];
        Point2D.Double dir = atStart
                ? GeometryUtils.normalize(GeometryUtils.subtract(path[0], path[1]))
                : GeometryUtils.normalize(GeometryUtils.subtract(path[path.length - 1], path[path.length - 2]));

        if (target.test(tip)) {
            return path;
        }
        Point2D.Double reached = null;
        for (double moved = EXTEND_STEP_PX; moved <= maxExtend; moved += EXTEND_STEP_PX) {
            Point2D.Double p = GeometryUtils.add(tip, GeometryUtils.scale(dir, moved));
            if (target.test(p)) {
                reached = p;
                break;
            }
        }
        if (reached == null) {
            return path;
        }
        Point2D.Double[] out = new Point2D.Double[path.length + 1];
        if (atStart) {
            out[0] = reached;
            System.arraycopy(path, 0, out, 1, path.length);
        } else {
            System.arraycopy(path, 0, out, 0, path.length);
            out[path.length] = reached;
        }
        return out;
    }

    /**
     * Arc-length position along the Purkinje polyline of the point where {@code fissurePts} comes
     * closest to it, found by sampling finely along the fissure's own path. Used to put the
     * fissures in anatomical order.
     */
    private static double purkinjeCrossingArc(Point2D.Double[] fissurePts, Point2D.Double[] purkPts,
            double[] purkCumulative) {
        double[] fissureCum = GeometryUtils.cumulativeLengths(fissurePts);
        double totalLen = fissureCum[fissureCum.length - 1];
        int samples = (int) Math.max(20, Math.min(300, totalLen));

        double bestDist = Double.POSITIVE_INFINITY;
        double bestPurkArc = 0;
        for (int i = 0; i <= samples; i++) {
            Point2D.Double p = GeometryUtils.pointAtArcLength(fissurePts, fissureCum, totalLen * i / samples);
            GeometryUtils.Projection proj = GeometryUtils.project(purkPts, purkCumulative, p);
            if (proj.distance < bestDist) {
                bestDist = proj.distance;
                bestPurkArc = proj.arcLength;
            }
        }
        return bestPurkArc;
    }

    private static double pathLength(Point2D.Double[] pts) {
        double len = 0;
        for (int i = 1; i < pts.length; i++) {
            len += pts[i - 1].distance(pts[i]);
        }
        return len;
    }

    private static Point2D.Double[] reversed(Point2D.Double[] arr) {
        Point2D.Double[] rev = new Point2D.Double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            rev[i] = arr[arr.length - 1 - i];
        }
        return rev;
    }

    private static PolygonRoi toPolygonRoi(Point2D.Double[] pts, int type) {
        int     n  = pts.length;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (float) pts[i].x;
            ys[i] = (float) pts[i].y;
        }
        return new PolygonRoi(xs, ys, n, type);
    }
}
