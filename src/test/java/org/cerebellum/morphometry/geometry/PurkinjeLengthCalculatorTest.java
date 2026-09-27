package org.cerebellum.morphometry.geometry;

import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurkinjeLengthCalculatorTest {

    private static ImagePlus image(double pixelWidth, double pixelHeight) {
        ImagePlus imp = new ImagePlus("test", new ByteProcessor(200, 100));
        Calibration cal = new Calibration();
        cal.pixelWidth = pixelWidth;
        cal.pixelHeight = pixelHeight;
        cal.setUnit("um");
        imp.setCalibration(cal);
        return imp;
    }

    private static PolygonRoi polyline(float... xy) {
        float[] xs = new float[xy.length / 2];
        float[] ys = new float[xy.length / 2];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = xy[2 * i];
            ys[i] = xy[2 * i + 1];
        }
        return new PolygonRoi(xs, ys, xs.length, Roi.POLYLINE);
    }

    @Test
    void totalLengthHandlesAnisotropicPixels() {
        PolygonRoi line = polyline(10, 10, 13, 14);
        assertEquals(Math.sqrt(6 * 6 + 4 * 4), PurkinjeLengthCalculator.totalLength(line, image(2, 1)), 1e-9);
    }

    @Test
    void clipsALongSegmentAtTheRegionBoundary() {
        // One 180 px segment crossing a 30 px wide region: the old midpoint rule could only ever
        // credit all or nothing of it.
        PolygonRoi line = polyline(10, 50.5f, 190, 50.5f);
        Roi region = new Roi(40, 0, 30, 100);
        assertEquals(30, PurkinjeLengthCalculator.lengthInside(line, image(1, 1), region), 0.3);
    }

    @Test
    void creditsBothStretchesWhenTheLineLeavesAndReEntersARegion() {
        PolygonRoi line = polyline(10, 50.5f, 190, 50.5f);
        Roi left = new Roi(20, 0, 20, 100);
        Roi right = new Roi(150, 0, 20, 100);
        ShapeRoi both = new ShapeRoi(left).or(new ShapeRoi(right));

        assertEquals(40, PurkinjeLengthCalculator.lengthInside(line, image(1, 1), both), 0.5);
        List<PolygonRoi> segments = PurkinjeLengthCalculator.segmentsInside(line, both);
        assertEquals(2, segments.size());
        double total = 0;
        for (PolygonRoi s : segments) {
            assertEquals(Roi.POLYLINE, s.getType());
            total += PurkinjeLengthCalculator.totalLength(s, image(1, 1));
        }
        assertEquals(40, total, 0.5, "exported segments must measure what the table reports");
    }

    @Test
    void lengthOutsideEveryRegionIsZero() {
        PolygonRoi line = polyline(10, 10, 20, 10);
        assertEquals(0, PurkinjeLengthCalculator.lengthInside(line, image(1, 1), new Roi(100, 50, 10, 10)), 0);
        assertTrue(PurkinjeLengthCalculator.segmentsInside(line, new Roi(100, 50, 10, 10)).isEmpty());
    }
}
