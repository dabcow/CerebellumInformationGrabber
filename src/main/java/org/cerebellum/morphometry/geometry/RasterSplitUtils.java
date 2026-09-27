package org.cerebellum.morphometry.geometry;

import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Raster (pixel-mask) alternative to {@code ShapeRoi}/{@code Area} Boolean operations, used
 * specifically where <b>piece identity</b> (not just total area) matters &mdash; i.e.
 * splitting a shape into its individual disjoint components after subtracting cutting strips.
 *
 * <h2>Why this exists</h2>
 * <p>Java2D's {@code java.awt.geom.Area}, which {@code ShapeRoi}'s Boolean operations are
 * built on, turned out to be unreliable on real (not synthetic) anatomical tracings: even a
 * single subtraction between two validly-nested, non-self-crossing polygons of a few hundred
 * vertices each fragmented into dozens of spurious extra pieces. Verified directly: on real
 * traced data, a plain {@code Cerebellum \ GranularWM} subtraction &mdash; with zero actual
 * boundary crossings between the two polygons &mdash; produced 77 pieces via {@code ShapeRoi},
 * scaling almost linearly with vertex count as the polygons were decimated (42 at half density,
 * 25 at a third, 12 at a fifth). Rasterizing to a pixel mask and using ordinary 8-connected
 * flood-fill labeling sidesteps that vector-geometry fragility entirely; the same subtraction
 * on the same data reliably gives the single connected piece the anatomy actually has.</p>
 *
 * <p>This is deliberately <em>not</em> used for whole-cerebellum area totals elsewhere in the
 * plugin (see {@link BooleanROIProcessor#area}), because those only need a correct total area,
 * which {@code ImageStatistics} already computes correctly no matter how fragmented the
 * underlying {@code ShapeRoi} is. It matters specifically in {@link FissurePartitioner}, which
 * needs to know how many distinct pieces resulted from each cut in order to assign them to the
 * right lobules.</p>
 *
 * <p>All masks are row-major {@code boolean[w * h]} arrays covering a "canvas" rectangle in image
 * coordinates. Everything here uses primitive arrays and queues: masks can cover tens of millions
 * of pixels for a whole-slide section, and boxing every pixel index (as earlier versions did)
 * cost gigabytes of heap and most of the run time.</p>
 */
final class RasterSplitUtils {

    private static final int[] DX8 = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DY8 = {-1, -1, -1, 0, 0, 1, 1, 1};

    private RasterSplitUtils() {
    }

    // -----------------------------------------------------------------------
    // Rasterization and mask arithmetic
    // -----------------------------------------------------------------------

    /** Rasterizes {@code roi} into a new mask covering {@code canvas} (canvas-relative indexing). */
    static boolean[] rasterize(Roi roi, Rectangle canvas) {
        boolean[] mask = new boolean[canvas.width * canvas.height];
        rasterizeInto(mask, roi, canvas);
        return mask;
    }

    /**
     * ORs the filled area of {@code roi} into {@code mask} (which covers {@code canvas}). Lines and
     * points have no fill area and leave the mask unchanged. Works directly on the ROI's mask
     * pixels rather than allocating a second canvas-sized array per ROI.
     */
    static void rasterizeInto(boolean[] mask, Roi roi, Rectangle canvas) {
        ImageProcessor roiMask = roi.getMask();
        Rectangle rb = roi.getBounds();
        if (roiMask == null) {
            if (roi.getType() != Roi.RECTANGLE || roi.getCornerDiameter() > 0) {
                return; // a line/point Roi has no fill area
            }
            roiMask = new ByteProcessor(rb.width, rb.height);
            roiMask.invert(); // a plain rectangle's mask is implicitly all-inside
        }
        byte[] px = (byte[]) roiMask.getPixels();
        int mw = roiMask.getWidth();
        int offX = rb.x - canvas.x;
        int offY = rb.y - canvas.y;
        for (int y = 0; y < roiMask.getHeight(); y++) {
            int cy = y + offY;
            if (cy < 0 || cy >= canvas.height) {
                continue;
            }
            int rowBase = cy * canvas.width;
            int maskRow = y * mw;
            for (int x = 0; x < mw; x++) {
                if (px[maskRow + x] == 0) {
                    continue;
                }
                int cx = x + offX;
                if (cx >= 0 && cx < canvas.width) {
                    mask[rowBase + cx] = true;
                }
            }
        }
    }

    /** In place: {@code mask &= !subtract}. */
    static void subtractInPlace(boolean[] mask, boolean[] subtract) {
        for (int i = 0; i < mask.length; i++) {
            if (subtract[i]) {
                mask[i] = false;
            }
        }
    }

    static long countTrue(boolean[] mask) {
        long n = 0;
        for (boolean b : mask) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    // -----------------------------------------------------------------------
    // Connected components
    // -----------------------------------------------------------------------

    /** One connected foreground component. */
    static final class Component {
        final int[] pixelIndices;
        final long sumX;
        final long sumY;

        Component(int[] pixelIndices, long sumX, long sumY) {
            this.pixelIndices = pixelIndices;
            this.sumX = sumX;
            this.sumY = sumY;
        }

        int size() {
            return pixelIndices.length;
        }

        /** Centroid in canvas-relative pixel-index coordinates. */
        double centroidX() {
            return (double) sumX / pixelIndices.length;
        }

        double centroidY() {
            return (double) sumY / pixelIndices.length;
        }
    }

    /**
     * A labelled mask: {@code labels[i]} is the index into {@link #components} owning pixel
     * {@code i}, or {@code -1} for background.
     */
    static final class Labeling {
        final int[] labels;
        final List<Component> components;

        Labeling(int[] labels, List<Component> components) {
            this.labels = labels;
            this.components = components;
        }

        long labelledArea() {
            long n = 0;
            for (Component c : components) {
                n += c.size();
            }
            return n;
        }
    }

    /** 8-connected flood-fill labeling of the foreground pixels in {@code mask} (w &times; h). */
    static Labeling connectedComponents(boolean[] mask, int w, int h) {
        int[] labels = new int[mask.length];
        Arrays.fill(labels, -1);
        List<Component> result = new ArrayList<>();
        IntQueue queue = new IntQueue();
        IntList pixels = new IntList();

        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || labels[start] != -1) {
                continue;
            }
            int label = result.size();
            pixels.clear();
            queue.clear();
            queue.add(start);
            labels[start] = label;
            long sumX = 0, sumY = 0;
            while (!queue.isEmpty()) {
                int idx = queue.poll();
                pixels.add(idx);
                int x = idx % w;
                int y = idx / w;
                sumX += x;
                sumY += y;
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX8[d];
                    int ny = y + DY8[d];
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                        continue;
                    }
                    int nidx = ny * w + nx;
                    if (mask[nidx] && labels[nidx] == -1) {
                        labels[nidx] = label;
                        queue.add(nidx);
                    }
                }
            }
            result.add(new Component(pixels.toArray(), sumX, sumY));
        }
        return new Labeling(labels, result);
    }

    /**
     * Turns the raw pieces left after subtracting the cutting strips into a complete partition of
     * {@code region} (the uncut shape), in three steps:
     * <ol>
     *   <li>Components of at least {@code minKeepArea} pixels are kept; smaller ones are slivers.</li>
     *   <li>The kept components are grown, geodesically and in lock-step, into every region pixel
     *       that no component owns &mdash; i.e. the cutting strips themselves. Each strip pixel
     *       therefore joins whichever piece is nearest, which puts the final boundary on the
     *       strip's centre line: the traced fissure. Without this, the strips' area was simply
     *       lost, and every lobule came out a few percent too small.</li>
     *   <li>Each sliver is then merged <em>whole</em> into the grown piece it shares the most
     *       border with (before growth a sliver is, by definition of a connected component, not
     *       adjacent to any other piece, so it has to happen afterwards), and a final growth pass
     *       picks up any strip pixels that only bordered slivers.</li>
     * </ol>
     * Region pixels that no kept piece can reach at all (an isolated speck) stay unlabelled.
     */
    static Labeling completePartition(Labeling raw, boolean[] region, int w, int h, long minKeepArea) {
        int n = raw.components.size();
        int[] remap = new int[n];
        int kept = 0;
        for (int c = 0; c < n; c++) {
            remap[c] = raw.components.get(c).size() >= minKeepArea ? kept++ : -1;
        }

        int[] labels = new int[raw.labels.length];
        Arrays.fill(labels, -1);
        boolean[] blocked = new boolean[raw.labels.length]; // sliver pixels, excluded from the first growth
        for (int i = 0; i < labels.length; i++) {
            int l = raw.labels[i];
            if (l >= 0) {
                if (remap[l] >= 0) {
                    labels[i] = remap[l];
                } else {
                    blocked[i] = true;
                }
            }
        }

        growInto(labels, region, blocked, w, h);

        for (int c = 0; c < n; c++) {
            if (remap[c] >= 0) {
                continue;
            }
            int[] pixels = raw.components.get(c).pixelIndices;
            int target = dominantNeighbourLabel(pixels, labels, kept, w, h);
            if (target >= 0) {
                for (int idx : pixels) {
                    labels[idx] = target;
                }
            }
        }

        growInto(labels, region, null, w, h);
        return fromLabels(labels, kept, w);
    }

    /**
     * Multi-source breadth-first growth: every unlabelled pixel of {@code region} (and not
     * {@code blocked}) that is 8-connected to a labelled pixel takes the label of whichever
     * labelled pixel reaches it first. Growth proceeds one ring at a time from all labels at once.
     */
    private static void growInto(int[] labels, boolean[] region, boolean[] blocked, int w, int h) {
        IntQueue queue = new IntQueue();
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] >= 0 && hasGrowableNeighbour(i, labels, region, blocked, w, h)) {
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int label = labels[idx];
            int x = idx % w;
            int y = idx / w;
            for (int d = 0; d < 8; d++) {
                int nx = x + DX8[d];
                int ny = y + DY8[d];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                    continue;
                }
                int nidx = ny * w + nx;
                if (labels[nidx] == -1 && region[nidx] && (blocked == null || !blocked[nidx])) {
                    labels[nidx] = label;
                    queue.add(nidx);
                }
            }
        }
    }

    private static boolean hasGrowableNeighbour(int idx, int[] labels, boolean[] region, boolean[] blocked,
            int w, int h) {
        int x = idx % w;
        int y = idx / w;
        for (int d = 0; d < 8; d++) {
            int nx = x + DX8[d];
            int ny = y + DY8[d];
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                continue;
            }
            int nidx = ny * w + nx;
            if (labels[nidx] == -1 && region[nidx] && (blocked == null || !blocked[nidx])) {
                return true;
            }
        }
        return false;
    }

    /** The label (in {@code [0, labelCount)}) most often 8-adjacent to {@code pixels}, or -1 if none is. */
    private static int dominantNeighbourLabel(int[] pixels, int[] labels, int labelCount, int w, int h) {
        int[] votes = new int[labelCount];
        for (int idx : pixels) {
            int x = idx % w;
            int y = idx / w;
            for (int d = 0; d < 8; d++) {
                int nx = x + DX8[d];
                int ny = y + DY8[d];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                    continue;
                }
                int l = labels[ny * w + nx];
                if (l >= 0) {
                    votes[l]++;
                }
            }
        }
        int best = -1;
        for (int l = 0; l < labelCount; l++) {
            if (votes[l] > 0 && (best < 0 || votes[l] > votes[best])) {
                best = l;
            }
        }
        return best;
    }

    /** Rebuilds the component list from a label array holding labels {@code 0 .. count-1}. */
    private static Labeling fromLabels(int[] labels, int count, int w) {
        IntList[] pixels = new IntList[count];
        long[] sumX = new long[count];
        long[] sumY = new long[count];
        for (int c = 0; c < count; c++) {
            pixels[c] = new IntList();
        }
        for (int i = 0; i < labels.length; i++) {
            int l = labels[i];
            if (l >= 0) {
                pixels[l].add(i);
                sumX[l] += i % w;
                sumY[l] += i / w;
            }
        }
        List<Component> comps = new ArrayList<>(count);
        for (int c = 0; c < count; c++) {
            comps.add(new Component(pixels[c].toArray(), sumX[c], sumY[c]));
        }
        return new Labeling(labels, comps);
    }

    // -----------------------------------------------------------------------
    // Topology
    // -----------------------------------------------------------------------

    /**
     * True when {@code mask}'s foreground encloses at least one hole &mdash; i.e. it's a ring
     * (annulus), not a simply-connected blob. Found by flood-filling the background inwards from
     * the canvas border (4-connected, the correct dual of 8-connected foreground): any background
     * left unreached is enclosed by foreground. A handful of enclosed pixels is required so a
     * stray rasterization artifact isn't mistaken for a hole.
     */
    static boolean hasHole(boolean[] mask, int w, int h) {
        boolean[] reached = new boolean[mask.length];
        IntQueue queue = new IntQueue();
        for (int x = 0; x < w; x++) {
            seed(queue, reached, mask, x);
            seed(queue, reached, mask, (h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            seed(queue, reached, mask, y * w);
            seed(queue, reached, mask, y * w + (w - 1));
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w, y = i / w;
            if (x > 0)     seed(queue, reached, mask, i - 1);
            if (x < w - 1) seed(queue, reached, mask, i + 1);
            if (y > 0)     seed(queue, reached, mask, i - w);
            if (y < h - 1) seed(queue, reached, mask, i + w);
        }
        int enclosed = 0;
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] && !reached[i] && ++enclosed > 32) {
                return true;
            }
        }
        return false;
    }

    private static void seed(IntQueue queue, boolean[] reached, boolean[] mask, int i) {
        if (!mask[i] && !reached[i]) {
            reached[i] = true;
            queue.add(i);
        }
    }

    // -----------------------------------------------------------------------
    // Vectorisation
    // -----------------------------------------------------------------------

    /**
     * Traces a single component's outline into a usable {@link Roi}, in absolute image
     * coordinates. Only the component's own bounding box is rasterized, not the whole canvas.
     */
    static Roi traceComponent(Component comp, int w, Rectangle canvas) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int idx : comp.pixelIndices) {
            int x = idx % w, y = idx / w;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        ByteProcessor bp = new ByteProcessor(bw, bh);
        byte[] px = (byte[]) bp.getPixels();
        for (int idx : comp.pixelIndices) {
            px[(idx / w - minY) * bw + (idx % w - minX)] = (byte) 255;
        }
        bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
        Roi traced = new ThresholdToSelection().convert(bp);
        Rectangle tb = traced.getBounds();
        traced.setLocation(tb.x + minX + canvas.x, tb.y + minY + canvas.y);
        return traced;
    }

    /**
     * Index of the component pixel closest to its centroid &mdash; a point guaranteed to lie
     * inside the component, even for a curved (e.g. U-shaped) lobule whose centroid does not.
     */
    static int interiorPixel(Component comp, int w) {
        double cx = comp.centroidX();
        double cy = comp.centroidY();
        int best = comp.pixelIndices[0];
        double bestD = Double.POSITIVE_INFINITY;
        for (int idx : comp.pixelIndices) {
            double dx = idx % w - cx;
            double dy = idx / w - cy;
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = idx;
            }
        }
        return best;
    }

    // -----------------------------------------------------------------------
    // Primitive collections (avoid boxing one Integer per pixel)
    // -----------------------------------------------------------------------

    /** Growable FIFO ring buffer of ints. */
    static final class IntQueue {
        private int[] buf = new int[1024];
        private int head;
        private int size;

        void add(int v) {
            if (size == buf.length) {
                int[] grown = new int[buf.length * 2];
                for (int i = 0; i < size; i++) {
                    grown[i] = buf[(head + i) % buf.length];
                }
                buf = grown;
                head = 0;
            }
            buf[(head + size) % buf.length] = v;
            size++;
        }

        int poll() {
            int v = buf[head];
            head = (head + 1) % buf.length;
            size--;
            return v;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void clear() {
            head = 0;
            size = 0;
        }
    }

    /** Growable list of ints. */
    static final class IntList {
        private int[] buf = new int[256];
        private int size;

        void add(int v) {
            if (size == buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[size++] = v;
        }

        void clear() {
            size = 0;
        }

        int[] toArray() {
            return Arrays.copyOf(buf, size);
        }
    }
}
