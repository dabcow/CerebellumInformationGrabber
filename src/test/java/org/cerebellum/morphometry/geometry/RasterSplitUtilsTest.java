package org.cerebellum.morphometry.geometry;

import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RasterSplitUtilsTest {

    private static final Rectangle CANVAS = new Rectangle(0, 0, 100, 60);
    private static final int W = CANVAS.width;
    private static final int H = CANVAS.height;

    @Test
    void rasterizesPlainRectangles() {
        // A plain rectangle Roi has no mask image; it used to rasterize as empty.
        boolean[] mask = RasterSplitUtils.rasterize(new Roi(10, 10, 20, 5), CANVAS);
        assertEquals(100, RasterSplitUtils.countTrue(mask));
    }

    @Test
    void labelsEightConnectedComponents() {
        boolean[] mask = new boolean[W * H];
        RasterSplitUtils.rasterizeInto(mask, new Roi(5, 5, 10, 10), CANVAS);
        RasterSplitUtils.rasterizeInto(mask, new Roi(15, 15, 10, 10), CANVAS); // touches diagonally
        RasterSplitUtils.rasterizeInto(mask, new Roi(60, 5, 10, 10), CANVAS);
        RasterSplitUtils.Labeling l = RasterSplitUtils.connectedComponents(mask, W, H);
        assertEquals(2, l.components.size());
        assertEquals(300, l.labelledArea());
    }

    @Test
    void completePartitionGivesTheCutStripBackToBothSides() {
        boolean[] bar = RasterSplitUtils.rasterize(new Roi(10, 20, 80, 20), CANVAS);   // 1600 px
        boolean[] cut = bar.clone();
        RasterSplitUtils.subtractInPlace(cut, RasterSplitUtils.rasterize(new Roi(46, 0, 8, H), CANVAS));
        RasterSplitUtils.Labeling raw = RasterSplitUtils.connectedComponents(cut, W, H);
        assertEquals(2, raw.components.size());
        assertEquals(1600 - 8 * 20, raw.labelledArea());

        RasterSplitUtils.Labeling done = RasterSplitUtils.completePartition(raw, bar, W, H, 10);
        assertEquals(2, done.components.size());
        assertEquals(1600, done.labelledArea(), "every pixel of the uncut shape is assigned");
        // The boundary lands on the strip's centre line (x = 50): 40 columns each side.
        assertEquals(800, done.components.get(0).size());
        assertEquals(800, done.components.get(1).size());
    }

    @Test
    void sliverIsMergedWholeIntoItsNeighbour() {
        boolean[] base = new boolean[W * H];
        RasterSplitUtils.rasterizeInto(base, new Roi(10, 20, 80, 20), CANVAS);
        RasterSplitUtils.rasterizeInto(base, new Roi(48, 10, 4, 10), CANVAS); // small tab above the cut
        boolean[] cut = base.clone();
        boolean[] strip = RasterSplitUtils.rasterize(new Roi(0, 18, W, 3), CANVAS); // cuts the tab off
        RasterSplitUtils.subtractInPlace(strip, RasterSplitUtils.rasterize(new Roi(0, 0, 48, H), CANVAS));
        RasterSplitUtils.subtractInPlace(strip, RasterSplitUtils.rasterize(new Roi(52, 0, 48, H), CANVAS));
        RasterSplitUtils.subtractInPlace(cut, strip);

        RasterSplitUtils.Labeling raw = RasterSplitUtils.connectedComponents(cut, W, H);
        assertEquals(2, raw.components.size(), "the tab is cut off as a sliver");

        RasterSplitUtils.Labeling done = RasterSplitUtils.completePartition(raw, base, W, H, 100);
        assertEquals(1, done.components.size(), "the sliver is not a section of its own");
        assertEquals(RasterSplitUtils.countTrue(base), done.labelledArea(), "and its area is kept");
    }

    @Test
    void detectsRingsAndDisks() {
        Rectangle canvas = new Rectangle(0, 0, 120, 120);
        ShapeRoi ring = new ShapeRoi(new OvalRoi(10, 10, 100, 100)).not(new ShapeRoi(new OvalRoi(40, 40, 40, 40)));
        assertTrue(RasterSplitUtils.hasHole(RasterSplitUtils.rasterize(ring, canvas), 120, 120));
        assertFalse(RasterSplitUtils.hasHole(RasterSplitUtils.rasterize(new OvalRoi(10, 10, 100, 100), canvas), 120, 120));
    }

    @Test
    void tracesAComponentBackToAnRoiInImageCoordinates() {
        Rectangle canvas = new Rectangle(-20, -10, 100, 60);
        boolean[] mask = RasterSplitUtils.rasterize(new Roi(5, 7, 12, 9), canvas);
        RasterSplitUtils.Labeling l = RasterSplitUtils.connectedComponents(mask, canvas.width, canvas.height);
        Roi traced = RasterSplitUtils.traceComponent(l.components.get(0), canvas.width, canvas);
        assertEquals(new Rectangle(5, 7, 12, 9), traced.getBounds());
    }
}
