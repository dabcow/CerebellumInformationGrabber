package org.cerebellum.morphometry.geometry;

import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayDeque;
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
 */
final class RasterSplitUtils {

    private RasterSplitUtils() {
    }

    /** Rasterizes {@code roi} into a boolean mask covering {@code canvas} (canvas-relative indexing). */
    static boolean[] rasterize(Roi roi, Rectangle canvas) {
        boolean[] mask = new boolean[canvas.width * canvas.height];
        ImageProcessor roiMask = roi.getMask();
        if (roiMask == null) {
            return mask; // a line/point Roi has no fill area
        }
        Rectangle rb = roi.getBounds();
        int offX = rb.x - canvas.x;
        int offY = rb.y - canvas.y;
        for (int y = 0; y < rb.height; y++) {
            int cy = y + offY;
            if (cy < 0 || cy >= canvas.height) {
                continue;
            }
            int rowBase = cy * canvas.width;
            for (int x = 0; x < rb.width; x++) {
                if (roiMask.getPixel(x, y) == 0) {
                    continue;
                }
                int cx = x + offX;
                if (cx < 0 || cx >= canvas.width) {
                    continue;
                }
                mask[rowBase + cx] = true;
            }
        }
        return mask;
    }

    /** In place: {@code mask &= !subtract}. */
    static void subtractInPlace(boolean[] mask, boolean[] subtract) {
        for (int i = 0; i < mask.length; i++) {
            if (subtract[i]) {
                mask[i] = false;
            }
        }
    }

    /** In place: {@code mask |= addition}. */
    static void orInPlace(boolean[] mask, boolean[] addition) {
        for (int i = 0; i < mask.length; i++) {
            if (addition[i]) {
                mask[i] = true;
            }
        }
    }

    /** One connected foreground component (8-connected). */
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

        /** Centroid in canvas-relative coordinates. */
        double centroidX() {
            return (double) sumX / pixelIndices.length;
        }

        double centroidY() {
            return (double) sumY / pixelIndices.length;
        }
    }

    /** 8-connected flood-fill labeling of the foreground pixels in {@code mask} (w &times; h). */
    static List<Component> connectedComponents(boolean[] mask, int w, int h) {
        int[] label = new int[mask.length];
        Arrays.fill(label, -1);
        List<Component> result = new ArrayList<>();
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || label[start] != -1) {
                continue;
            }
            List<Integer> pixels = new ArrayList<>();
            long sumX = 0, sumY = 0;
            queue.clear();
            queue.add(start);
            label[start] = result.size();
            while (!queue.isEmpty()) {
                int idx = queue.poll();
                pixels.add(idx);
                int x = idx % w;
                int y = idx / w;
                sumX += x;
                sumY += y;
                for (int d = 0; d < 8; d++) {
                    int nx = x + dx[d];
                    int ny = y + dy[d];
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                        continue;
                    }
                    int nidx = ny * w + nx;
                    if (mask[nidx] && label[nidx] == -1) {
                        label[nidx] = result.size();
                        queue.add(nidx);
                    }
                }
            }
            int[] arr = new int[pixels.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = pixels.get(i);
            }
            result.add(new Component(arr, sumX, sumY));
        }
        return result;
    }

    /**
     * Absorbs every component smaller than {@code minKeepArea} into the adjacent kept component
     * it shares the most 8-connected border with, and returns only the kept (now enlarged)
     * components. This removes the spurious slivers that a straight-line fissure shaves off a
     * folded layer band when its cut crosses the band's edge at a shallow angle — without
     * discarding that tissue's area, which simply dropping the sliver would. A small component
     * with no kept neighbour (isolated speck) is dropped, as before.
     */
    static List<Component> mergeSmall(List<Component> comps, int w, int h, long minKeepArea) {
        int nc = comps.size();
        int[] label = new int[w * h];
        Arrays.fill(label, -1);
        for (int c = 0; c < nc; c++) {
            for (int idx : comps.get(c).pixelIndices) {
                label[idx] = c;
            }
        }
        boolean[] keep = new boolean[nc];
        for (int c = 0; c < nc; c++) {
            keep[c] = comps.get(c).size() >= minKeepArea;
        }
        // Each small component picks the kept component it borders most; -1 means "no kept neighbour".
        int[] target = new int[nc];
        Arrays.fill(target, -1);
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        for (int c = 0; c < nc; c++) {
            if (keep[c]) {
                continue;
            }
            java.util.HashMap<Integer, Integer> border = new java.util.HashMap<>();
            for (int idx : comps.get(c).pixelIndices) {
                int x = idx % w, y = idx / w;
                for (int d = 0; d < 8; d++) {
                    int nx = x + dx[d], ny = y + dy[d];
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                        continue;
                    }
                    int nl = label[ny * w + nx];
                    if (nl >= 0 && keep[nl]) {
                        border.merge(nl, 1, Integer::sum);
                    }
                }
            }
            int best = -1, bestCount = -1;
            for (java.util.Map.Entry<Integer, Integer> e : border.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    best = e.getKey();
                }
            }
            target[c] = best;
        }
        // Rebuild kept components, folding in the pixels of the small ones assigned to them.
        List<Component> result = new ArrayList<>();
        for (int c = 0; c < nc; c++) {
            if (!keep[c]) {
                continue;
            }
            List<Integer> pixels = new ArrayList<>();
            long sumX = comps.get(c).sumX;
            long sumY = comps.get(c).sumY;
            for (int idx : comps.get(c).pixelIndices) {
                pixels.add(idx);
            }
            for (int s = 0; s < nc; s++) {
                if (target[s] == c) {
                    for (int idx : comps.get(s).pixelIndices) {
                        pixels.add(idx);
                        sumX += idx % w;
                        sumY += idx / w;
                    }
                }
            }
            int[] arr = new int[pixels.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = pixels.get(i);
            }
            result.add(new Component(arr, sumX, sumY));
        }
        return result;
    }

    /** Traces a single component's outline into a usable {@link Roi}, in absolute image coordinates. */
    static Roi traceComponent(Component comp, int w, int h, Rectangle canvas) {
        ByteProcessor bp = new ByteProcessor(w, h);
        byte[] px = (byte[]) bp.getPixels();
        for (int idx : comp.pixelIndices) {
            px[idx] = (byte) 255;
        }
        bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
        Roi traced = new ThresholdToSelection().convert(bp);
        Rectangle tb = traced.getBounds();
        traced.setLocation(tb.x + canvas.x, tb.y + canvas.y);
        return traced;
    }
}
