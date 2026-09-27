package org.cerebellum.morphometry.geometry;

import ij.IJ;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.PartitionSet;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Partitions the cerebellum into {@code N+1} lobule regions from {@code N} traced fissures
 * (standard case: 7 fissures &rarr; 8 lobules, but any count is supported &mdash; see {@link
 * #buildLobuleLabels}) using a <b>subtract-and-split</b> strategy (equivalent to the ROI
 * Manager's XOR&thinsp;+&thinsp;Split), applied <b>once to the whole Grey Matter ring</b> (each
 * lobule's molecular and granular parts are derived from the same piece downstream by {@link
 * org.cerebellum.morphometry.geometry.PartitionClipper}), with an <b>adaptive strip-width
 * search</b> and <b>sliver merging</b> to make the split robust.
 *
 * <h2>Four things went wrong before landing on this design</h2>
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
 *   <li><b>One long cut spanning both layers with a single fixed strip width.</b> Even
 *       after fixing the topology by partitioning Grey Matter (which does have a
 *       White-Matter hole), a single strip width picked as a fixed fraction of the whole
 *       image diagonal turned out to sit in an unreliable zone: Java2D's {@code Area}
 *       silently fragmented cuts into spurious extra pieces when the strip was too thin
 *       relative to the local vertex spacing of the boundary it crossed, but a strip wide
 *       enough to be safe in one region was wide enough to merge neighbouring cuts
 *       together in another. No single fixed width could be shown to be safe for
 *       arbitrary real input, because the safe window's location depends on how finely
 *       the user traced their ROIs and on the local anatomy scale &mdash; neither of
 *       which is known in advance.</li>
 *   <li><b>Assuming the traced fissure already spans the tissue.</b> Where a fissure stops is
 *       up to whoever traced it: the README explicitly allows stopping at the Purkinje line,
 *       and even a fissure meant to run the full depth rarely lands its deep end exactly inside
 *       white matter. Delimiting each layer's portion by that layer's own boundary and padding
 *       the ends by a small fixed overshoot therefore left the granular portion as a stub
 *       whenever the trace stopped short — the granular ring went uncut and two lobules stayed
 *       fused, so the fissure was silently "not respected". The fix is to stop depending on how
 *       far the trace happens to reach: <em>extend</em> each fissure along its own end-tangents
 *       until it provably spans the tissue (pial end just outside the Cerebellum, deep end
 *       inside White Matter), which is exactly what a partition boundary has to do.</li>
 * </ol>
 *
 * <h2>This design</h2>
 * <ol>
 *   <li><b>Orient</b> each fissure pial&rarr;white-matter by the anatomical <b>nesting depth</b>
 *       of its endpoints (inside White Matter &gt; inside Granular+WM &gt; inside Cerebellum
 *       &gt; outside), which is robust to how the tissue folds; ties fall back to proximity to
 *       the White Matter, then Cerebellum, outline. Separately <b>find where it crosses the
 *       Purkinje line</b> by sampling along its own path &mdash; used only to sort the fissures
 *       into anatomical (2Cb&rarr;10Cb) order.</li>
 *   <li><b>Build one spanning cut per fissure</b> by keeping the user's traced vertices and
 *       extending the two ends along their own tangents until the cut runs from just outside the
 *       Cerebellum to inside White Matter. Extension stops the instant its target is reached
 *       (so a cut can't re-emerge through unrelated tissue), is distance-capped, and leaves an
 *       end as-traced if its target is unreachable within the cap rather than shooting a blind
 *       straight line across the section.</li>
 *   <li><b>Partition the Grey Matter ring once</b> ({@code Cerebellum \ WhiteMatter}) with those
 *       spanning cuts, instead of splitting the Molecular and Granular layers separately and
 *       pairing the resulting pieces. The two layers fragment into different arrangements (and
 *       often different topology &mdash; an open molecular ribbon vs. a closed granular ring), so
 *       pairing piece <i>i</i> of one with piece <i>i</i> of the other fused unrelated lobules.
 *       One partition of one shape makes a lobule's molecular and granular parts, by
 *       construction, the same lobule.</li>
 *   <li><b>Search strip widths, then merge slivers.</b> Try a small set of strip widths (as
 *       fractions of the average cut length, thinnest first) and keep the first that yields the
 *       expected piece count over most of the grey matter's area. Any sub-threshold sliver
 *       &mdash; e.g. the corner a straight-line fissure shaves off a folded band &mdash; is
 *       merged into its largest neighbour rather than kept as a spurious section or dropped
 *       (which would lose its area).</li>
 *   <li><b>Derive per-lobule molecular and granular shapes downstream.</b> {@link
 *       org.cerebellum.morphometry.geometry.PartitionClipper} intersects each lobule footprint
 *       with the whole-cerebellum Molecular and Granular layers, so no combination step is
 *       needed here &mdash; the single grey-matter footprint carries everything.</li>
 * </ol>
 */
public final class FissurePartitioner {

    /**
     * Anatomical subsection labels for the standard rodent vermis scheme (2Cb nearest the
     * Purkinje start), used when there are exactly 7 fissures. See {@link #buildLobuleLabels}.
     */
    private static final String[] STANDARD_LOBULE_LABELS = {
            "2Cb", "3Cb", "4/5Cb", "6Cb", "7Cb", "8Cb", "9Cb", "10Cb"
    };

    /**
     * Builds subsection labels for {@code fissureCount} fissures, which produce {@code
     * fissureCount + 1} subsections. Ten lobules across 7 fissures (2Cb, 3Cb, 4/5Cb, 6Cb,
     * 7Cb, 8Cb, 9Cb, 10Cb — lobule 1 is conventionally fused/not separately measured) is the
     * standard scheme for a midline sagittal section of rodent vermis, but it isn't
     * universal: off-midline sagittal cuts, coronal or horizontal sections, damaged tissue,
     * and other species all have a different lobule count or don't follow this numbering at
     * all. Rather than assume the standard scheme always applies, this only uses the named
     * labels when the fissure count actually matches it (7); any other count gets generic
     * "Section N" labels instead, since a specific anatomical name would be a guess.
     */
    private static String[] buildLobuleLabels(int fissureCount) {
        return labelsForSubsectionCount(fissureCount + 1);
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
            return STANDARD_LOBULE_LABELS;
        }
        String[] labels = new String[count];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "Section " + (i + 1);
        }
        return labels;
    }

    /**
     * Candidate strip half-widths to try, each expressed as a fraction of the current
     * stage's own average local cut length. Tried thinnest-first; the first one that
     * yields exactly N+1 valid pieces (N = number of fissures) covering most of the
     * stage's area is used. Three
     * candidates spanning a wide range are enough in practice — Java2D's {@code Area}
     * turned out to be robust across a wide width range once strips are compared against
     * a boundary with realistic (not pathologically dense) vertex spacing, so this exists
     * as a safety net for unusually dense tracing rather than as the primary mechanism.
     */
    private static final double[] WIDTH_FRACTION_CANDIDATES = {0.02, 0.06, 0.15};
    private static final double MIN_ABS_HALF_WIDTH_PX = 1.0;

    /** A width candidate is accepted only if the surviving pieces retain at least this much of the stage's area. */
    private static final double MIN_ACCEPTABLE_COVERAGE = 0.75;

    /** Pieces smaller than this fraction of the stage's own base-shape area are treated as slivers
     * and merged into their largest neighbour rather than kept as separate lobules. Set above the
     * ~1% slivers a straight-line fissure shaves off a folded band, but well below any genuine
     * lobule (typically many percent of the layer). */
    private static final double MIN_PIECE_AREA_FRACTION = 0.015; // 1.5%

    private FissurePartitioner() {
    }

    public static PartitionSet partition(LayerSet layers) {
        List<PolygonRoi> rawFissures = layers.getFissures();
        if (rawFissures.isEmpty()) {
            throw new IllegalStateException(
                    "FissurePartitioner requires at least 1 fissure (validated upstream); got 0");
        }

        PolygonRoi purkinje = layers.getPurkinje();
        Point2D.Double[] purkPoints     = GeometryUtils.extractPoints(purkinje);
        double[]         purkCumulative = GeometryUtils.cumulativeLengths(purkPoints);
        double           purkTotalLength = purkCumulative[purkCumulative.length - 1];
        Point2D.Double[] cerebellumBoundary = GeometryUtils.extractPoints(layers.getCerebellum());

        Roi cerebellumRoi  = layers.getCerebellum();
        Roi granularWMRoi  = layers.getGranularWM();
        Roi whiteMatterRoi = layers.getWhiteMatter();
        Point2D.Double[] whiteMatterBoundary = GeometryUtils.extractPoints(whiteMatterRoi);

        // -----------------------------------------------------------------------
        // Orient each fissure OUTER(pial)→INNER(white-matter) by the anatomical NESTING DEPTH
        // of its two endpoints, then find where each crosses the Purkinje line (for 2Cb→10Cb
        // ordering only).
        //
        // Depth, not raw distance to the Cerebellum outline, decides which end is pial. In a
        // tightly folded vermis the deep (white-matter) end of a fissure often sits closer to a
        // NEIGHBOURING folium's pial surface than the fissure's own mouth does, so the old
        // nearest-outline test silently flipped such fissures. A flipped fissure made the
        // molecular cut collapse to a stub while the granular cut overshot the whole layer, so
        // the fissure was "not respected" there. An endpoint inside White Matter is deeper than
        // one only inside Granular+WM, which is deeper than one merely inside the Cerebellum,
        // which is deeper than one outside it — a test that doesn't care how the tissue folds.
        // Ties (e.g. both ends still in the molecular layer for a short trace) fall back to
        // proximity to the White Matter outline (inner end is nearer it), then to the outline.
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

            Crossing crossing = findPurkinjeCrossing(pts, purkPoints, purkCumulative);
            oriented.add(new OrientedFissure(pts, crossing));
        }
        oriented.sort(Comparator.comparingDouble(f -> f.crossing.purkinjeArc));

        int n = oriented.size(); // however many fissures were traced

        // -----------------------------------------------------------------------
        // Build ONE spanning cut per fissure that is guaranteed to sever BOTH layer rings.
        //
        // A layer ring is only severed by a curve that crosses it from OUTSIDE its outer edge
        // to INSIDE its inner edge. The previous version trusted the user's trace to already
        // reach that far and, when it didn't, delimited each layer's portion by that layer's
        // boundary and padded the ends by a small fixed overshoot. But the README explicitly
        // lets a fissure stop at the Purkinje line — well short of the white matter — so on a
        // normally-traced section the granular portion collapsed to a stub, the granular ring
        // was never cut, and two lobules stayed fused: the fissure was silently "not respected".
        //
        // Instead, deliver what the README already promises: EXTEND each fissure along its own
        // end-tangents until it provably spans the tissue — the pial end pushed just outside the
        // Cerebellum outline, the deep end pushed until it is inside White Matter. The middle
        // keeps the user's traced shape exactly, so real fissure curvature is respected; only
        // the two ends are synthesised, and only as far as needed. Extension stops the instant
        // its target is reached (so a cut can't run on and re-emerge through unrelated tissue),
        // is distance-capped, and simply leaves the end as-traced if a target is unreachable
        // within the cap rather than shooting a straight line blindly across the section.
        //
        // The SAME spanning cut drives both stages: any part of it lying in the other layer or
        // in a ring's central hole is inert for that stage, so one cut cleanly does both jobs.
        // The Purkinje crossing is still computed, but only for ORDERING the fissures.
        // -----------------------------------------------------------------------
        // Partition ONE shape — the Grey Matter ring ({@code Cerebellum \ WhiteMatter}) — into
        // per-lobule footprints, and let PartitionClipper derive each lobule's molecular and
        // granular parts downstream by intersecting that footprint with the whole-layer shapes.
        //
        // The previous version split the Molecular and Granular layers SEPARATELY and paired the
        // resulting pieces by their sorted position along the Purkinje line. That pairing is only
        // valid if both layers fragment into the same arrangement of pieces — which they do not
        // in practice. The two layers have different shapes (and often different topology: an
        // open molecular ribbon vs. a closed granular ring), so the same cuts split them into
        // pieces that don't line up, and pairing piece i of one with piece i of the other fused
        // molecular tissue from one lobule with granular tissue from a completely different one.
        // Partitioning a single shape removes the pairing entirely: a lobule's molecular and
        // granular parts are, by construction, the same lobule.
        // -----------------------------------------------------------------------
        ShapeRoi greyBase = BooleanROIProcessor.subtract(cerebellumRoi, whiteMatterRoi);

        double diagonal = GeometryUtils.diagonal(cerebellumRoi.getBounds());
        List<Segment>    spanningCuts = new ArrayList<>(n);
        List<PolygonRoi> overlayLines = new ArrayList<>(n);
        for (OrientedFissure f : oriented) {
            Point2D.Double[] cut = spanningCut(f.points, cerebellumRoi, whiteMatterRoi, diagonal);
            spanningCuts.add(new Segment(cut, pathLength(cut)));
            overlayLines.add(toPolygonRoi(cut, Roi.POLYLINE));
        }

        List<ArcPiece> lobulePieces =
                partitionRing(greyBase, spanningCuts, purkPoints, purkCumulative, "grey matter");

        int count = lobulePieces.size();
        String[] labels = FissurePartitioner.labelsForSubsectionCount(count);
        List<PartitionSet.Partition> partitions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ShapeRoi region = lobulePieces.get(i).shape;
            // Purkinje span for this section: from this piece's start to the next piece's start.
            double arcStart = lobulePieces.get(i).arcLength;
            double arcEnd   = (i + 1 < count) ? lobulePieces.get(i + 1).arcLength : purkTotalLength;
            partitions.add(new PartitionSet.Partition(labels[i], region, arcStart, arcEnd));
        }

        // overlayLines already holds each fissure's true spanning cut (built above), so the
        // white overlay shows exactly what divided the layers — making any remaining "not
        // respected" case directly visible against the tissue instead of hidden.
        return new PartitionSet(partitions, overlayLines);
    }

    // -----------------------------------------------------------------------
    // Core: subtract-and-split one "ring" shape with an adaptive strip-width search.
    // -----------------------------------------------------------------------

    /** One split piece, with where its centroid falls along the Purkinje line (used for ordering). */
    private static final class ArcPiece {
        final ShapeRoi shape;
        final double arcLength;

        ArcPiece(ShapeRoi shape, double arcLength) {
            this.shape = shape;
            this.arcLength = arcLength;
        }
    }

    /**
     * Subtracts thin strips built from {@code cuts} out of {@code baseShape} and splits the
     * result, trying increasingly wide strips until one yields the expected number of pieces
     * covering most of {@code baseShape}'s area. Returns the pieces ordered by where their
     * centroid falls along the Purkinje line.
     *
     * <p><b>How many pieces to expect depends on the shape's topology.</b> An open ribbon is
     * cut into {@code N+1} pieces by {@code N} cuts, but a closed ring (an annulus — a shape
     * with a hole in it) is cut into only {@code N}: the first cut merely opens the ring into
     * a ribbon without separating anything. Cerebellar layers are normally rings, since the
     * layer wraps all the way around the white matter core; they're only ribbons when the
     * ring has been deliberately pinched open at the peduncle (see the README's "Closing the
     * loop"). This is detected here from the mask itself rather than assumed, because getting
     * it wrong made a perfectly good split look like a failure — the code would demand N+1,
     * never get it, fall back to a bad attempt, and mislabel the results.</p>
     *
     * <p>Works on a rasterized pixel mask with 8-connected flood-fill labeling, not on
     * {@code ShapeRoi}/{@code Area} directly — see {@link RasterSplitUtils} for why.</p>
     */
    private static List<ArcPiece> partitionRing(ShapeRoi baseShape, List<Segment> cuts,
            Point2D.Double[] purkPoints, double[] purkCumulative, String stageName) {

        Rectangle bounds = baseShape.getBounds();
        int margin = (int) Math.max(50, 0.08 * Math.max(bounds.width, bounds.height));
        Rectangle canvas = new Rectangle(bounds.x - margin, bounds.y - margin,
                bounds.width + 2 * margin, bounds.height + 2 * margin);
        int w = canvas.width, h = canvas.height;

        boolean[] baseMask = RasterSplitUtils.rasterize(baseShape, canvas);
        long baseArea = countTrue(baseMask);
        long minPieceArea = (long) (baseArea * MIN_PIECE_AREA_FRACTION);

        boolean isRing = hasHole(baseMask, w, h);
        int expected = cuts.size() + (isRing ? 0 : 1);
        IJ.log("[Cerebellar Morphometry] " + stageName + " is "
                + (isRing ? "a closed ring, so " + cuts.size() + " fissure(s) split it into "
                          : "an open ribbon, so " + cuts.size() + " fissure(s) split it into ")
                + expected + " section(s).");

        double avgLen = 0;
        for (Segment s : cuts) {
            avgLen += s.refLength;
        }
        avgLen /= cuts.size();

        List<RasterSplitUtils.Component> bestFiltered = null;
        long   bestArea = -1;
        int    bestDiff = Integer.MAX_VALUE;

        for (double frac : WIDTH_FRACTION_CANDIDATES) {
            double halfWidth = Math.max(MIN_ABS_HALF_WIDTH_PX, avgLen * frac);

            boolean[] stripsMask = new boolean[w * h];
            for (Segment s : cuts) {
                Point2D.Double[] stripPts = GeometryUtils.polylineStrip(s.pts, halfWidth);
                Roi stripRoi = toPolygonRoi(stripPts, Roi.POLYGON);
                RasterSplitUtils.orInPlace(stripsMask, RasterSplitUtils.rasterize(stripRoi, canvas));
            }

            boolean[] divided = baseMask.clone();
            RasterSplitUtils.subtractInPlace(divided, stripsMask);

            List<RasterSplitUtils.Component> raw = RasterSplitUtils.connectedComponents(divided, w, h);
            // Fold each sub-threshold sliver into its largest neighbour rather than dropping it,
            // so a straight fissure shaving a corner off a folded band doesn't spawn a spurious
            // extra section (and no tissue area is lost). See RasterSplitUtils.mergeSmall.
            List<RasterSplitUtils.Component> filtered =
                    RasterSplitUtils.mergeSmall(raw, w, h, minPieceArea);
            long filteredArea = 0;
            for (RasterSplitUtils.Component c : filtered) {
                filteredArea += c.size();
            }

            boolean countOk    = filtered.size() == expected;
            boolean coverageOk = filteredArea >= baseArea * MIN_ACCEPTABLE_COVERAGE;
            if (countOk && coverageOk) {
                return traceAndSort(filtered, canvas, w, h, purkPoints, purkCumulative);
            }

            int diff = Math.abs(filtered.size() - expected);
            if (diff < bestDiff || (diff == bestDiff && filteredArea > bestArea)) {
                bestDiff     = diff;
                bestArea     = filteredArea;
                bestFiltered = filtered;
            }
        }

        IJ.log("[Cerebellar Morphometry] Warning: could not find a strip width that cleanly splits the "
                + stageName + " into " + expected + " pieces after trying " + WIDTH_FRACTION_CANDIDATES.length
                + " candidate widths. Using the closest attempt (" + (bestFiltered == null ? 0 : bestFiltered.size())
                + " pieces). Consider retracing the fissures or layer boundaries with smoother lines.");
        return traceAndSort(bestFiltered == null ? new ArrayList<>() : bestFiltered, canvas, w, h,
                purkPoints, purkCumulative);
    }

    private static long countTrue(boolean[] mask) {
        long n = 0;
        for (boolean b : mask) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /**
     * Traces each accepted component's outline into a {@link ShapeRoi} (absolute image
     * coordinates) and returns them ordered by where their centroid falls along the Purkinje
     * line. Tracing only happens here, for the winning candidate — not during the width search.
     */
    private static List<ArcPiece> traceAndSort(List<RasterSplitUtils.Component> components, Rectangle canvas,
            int w, int h, Point2D.Double[] purkPoints, double[] purkCumulative) {
        List<ArcPiece> result = new ArrayList<>(components.size());
        for (RasterSplitUtils.Component c : components) {
            Point2D.Double centroid = new Point2D.Double(c.centroidX() + canvas.x, c.centroidY() + canvas.y);
            GeometryUtils.Projection proj = GeometryUtils.project(purkPoints, purkCumulative, centroid);
            Roi traced = RasterSplitUtils.traceComponent(c, w, h, canvas);
            result.add(new ArcPiece(new ShapeRoi(traced), proj.arcLength));
        }
        result.sort(Comparator.comparingDouble(p -> p.arcLength));
        return result;
    }

    /**
     * True when {@code mask}'s foreground encloses at least one hole — i.e. it's a ring
     * (annulus), not a simply-connected blob. Found by flood-filling the background inwards
     * from the canvas border: any background left unreached is enclosed by foreground.
     */
    private static boolean hasHole(boolean[] mask, int w, int h) {
        boolean[] reached = new boolean[mask.length];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            queue.add(x);
            queue.add((h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            queue.add(y * w);
            queue.add(y * w + (w - 1));
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            if (i < 0 || i >= mask.length || reached[i] || mask[i]) {
                continue;
            }
            reached[i] = true;
            int x = i % w, y = i / w;
            if (x > 0)     queue.add(i - 1);
            if (x < w - 1) queue.add(i + 1);
            if (y > 0)     queue.add(i - w);
            if (y < h - 1) queue.add(i + w);
        }
        // Any background pixel the border flood couldn't reach is inside a hole. Require a
        // few of them so a stray one-pixel rasterization artifact isn't mistaken for a hole.
        int enclosed = 0;
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] && !reached[i] && ++enclosed > 32) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    private static final class OrientedFissure {
        final Point2D.Double[] points; // index 0 = pial end, last = WM end
        final Crossing crossing;       // where this fissure crosses the Purkinje line

        OrientedFissure(Point2D.Double[] points, Crossing crossing) {
            this.points   = points;
            this.crossing = crossing;
        }
    }

    /** Where a fissure's own path crosses the Purkinje line. */
    private static final class Crossing {
        final Point2D.Double point;
        final double pathArc;     // arc-length along the fissure's own path
        final double purkinjeArc; // arc-length along the Purkinje polyline

        Crossing(Point2D.Double point, double pathArc, double purkinjeArc) {
            this.point       = point;
            this.pathArc     = pathArc;
            this.purkinjeArc = purkinjeArc;
        }
    }

    private static final class Segment {
        final Point2D.Double[] pts; // spanning cut polyline: outside Cerebellum → inside White Matter
        /** Total cut length — used to scale the strip half-width in the adaptive split search. */
        final double refLength;

        Segment(Point2D.Double[] pts, double refLength) {
            this.pts = pts;
            this.refLength = refLength;
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Distance a spanning-cut end is grown per iteration while searching for its target region. */
    private static final double EXTEND_STEP_PX = 2.0;

    /**
     * Anatomical nesting depth of a point: 3 inside White Matter, 2 inside Granular+WM, 1 inside
     * the Cerebellum, 0 outside it. Used to orient a fissure by which endpoint is deeper, which
     * is robust to how the tissue folds (unlike raw distance to the Cerebellum outline).
     */
    private static int nestingDepth(Point2D.Double p, Roi cerebellum, Roi granularWM, Roi whiteMatter) {
        if (contains(whiteMatter, p)) return 3;
        if (contains(granularWM, p))  return 2;
        if (contains(cerebellum, p))  return 1;
        return 0;
    }

    /** Null-safe {@link Roi#contains(int, int)} at the rounded pixel of {@code p}. */
    private static boolean contains(Roi roi, Point2D.Double p) {
        return roi != null && roi.contains((int) Math.round(p.x), (int) Math.round(p.y));
    }

    /**
     * Turns an oriented (pial&rarr;white-matter) fissure into a cut guaranteed to span the whole
     * tissue: its pial end is grown outward until it lies just outside the Cerebellum, and its
     * deep end is grown inward until it lies inside White Matter. The user's traced vertices in
     * between are preserved untouched, so genuine fissure curvature is respected — only the two
     * ends are synthesised, along the fissure's own end-tangents, and only as far as needed.
     */
    private static Point2D.Double[] spanningCut(Point2D.Double[] oriented, Roi cerebellum,
            Roi whiteMatter, double diagonal) {
        double capOuter = extensionCap(oriented[0], cerebellum, diagonal);
        Point2D.Double[] withPial = extendEndUntil(oriented, 0, p -> !contains(cerebellum, p), capOuter);

        int last = withPial.length - 1;
        double capInner = extensionCap(withPial[last], whiteMatter, diagonal);
        return extendEndUntil(withPial, last, p -> contains(whiteMatter, p), capInner);
    }

    /**
     * How far {@code extendEndUntil} may grow an end before giving up. Scales with the tip's
     * current distance to the target region's outline (so a nearby target gets a short leash and
     * a far one a longer leash), floored so a tip already on the boundary can still step across,
     * and hard-capped at a fraction of the section diagonal so no cut can ever run away.
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
     * steps, until {@code target} accepts the new tip; the reached point is then appended (end 0
     * prepended, otherwise appended). Returns {@code path} unchanged if the tip already satisfies
     * {@code target} or if it is not satisfied within {@code maxExtend} — never a blind straight
     * shot across the section.
     *
     * @param whichEnd 0 to grow the start end, {@code path.length - 1} (any non-zero) to grow the finish end
     */
    private static Point2D.Double[] extendEndUntil(Point2D.Double[] path, int whichEnd,
            java.util.function.Predicate<Point2D.Double> target, double maxExtend) {
        if (path.length < 2 || maxExtend <= 0) {
            return path;
        }
        boolean atStart = whichEnd == 0;
        Point2D.Double tip = atStart ? path[0] : path[path.length - 1];
        Point2D.Double dir = atStart
                ? GeometryUtils.normalize(GeometryUtils.subtract(path[0], path[1]))
                : GeometryUtils.normalize(GeometryUtils.subtract(path[path.length - 1], path[path.length - 2]));

        if (target.test(tip)) {
            return path; // already spans past this edge
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
            return path; // target unreachable within the cap: leave the end as the user traced it
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
     * Finds where {@code fissurePts} (a short pial-to-WM traced path) comes closest to the
     * Purkinje polyline, by sampling finely along the fissure's own path. This is the point
     * used both to split the fissure into its molecular and granular portions, and (via its
     * Purkinje arc-length) to order lobules 2Cb→10Cb.
     */
    private static Crossing findPurkinjeCrossing(Point2D.Double[] fissurePts, Point2D.Double[] purkPts, double[] purkCumulative) {
        double[] fissureCum = GeometryUtils.cumulativeLengths(fissurePts);
        double totalLen = fissureCum[fissureCum.length - 1];
        int samples = (int) Math.max(20, Math.min(300, totalLen));

        double bestDist = Double.POSITIVE_INFINITY;
        Point2D.Double bestPoint = fissurePts[0];
        double bestPathArc = 0;
        double bestPurkArc = 0;
        for (int i = 0; i <= samples; i++) {
            double arc = totalLen * i / samples;
            Point2D.Double p = GeometryUtils.pointAtArcLength(fissurePts, fissureCum, arc);
            GeometryUtils.Projection proj = GeometryUtils.project(purkPts, purkCumulative, p);
            if (proj.distance < bestDist) {
                bestDist    = proj.distance;
                bestPoint   = p;
                bestPathArc = arc;
                bestPurkArc = proj.arcLength;
            }
        }
        return new Crossing(bestPoint, bestPathArc, bestPurkArc);
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
