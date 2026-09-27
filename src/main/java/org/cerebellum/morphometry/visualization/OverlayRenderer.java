package org.cerebellum.morphometry.visualization;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import org.cerebellum.morphometry.measurement.MeasurementEngine.IntermediateGeometry;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.PartitionSet;

import java.awt.*;
import java.util.Map;
import java.util.SortedMap;

/**
 * Adds a non-destructive vector overlay to the active image representing each computed layer:
 *
 * <ul>
 *   <li>Grey Matter: blue</li>
 *   <li>Granular Layer: green</li>
 *   <li>Molecular Layer: yellow</li>
 *   <li>Purkinje line: red</li>
 *   <li>Partition cut-lines (extended fissures): white</li>
 * </ul>
 *
 * <p>Optionally, if {@code colorSubsections} is {@code true}, each lobule partition
 * receives a distinct semitransparent fill (cycling through a 12-color palette if there
 * are more lobules than colors), making it easy to visually verify that the geometric
 * partitioning matches the anatomy.</p>
 *
 * <p>When multiple instances are present (see {@link
 * org.cerebellum.morphometry.geometry.ROIValidator}'s "Multiple instances" section),
 * {@link #renderAll} draws all of them onto the same image in a single overlay, prefixing
 * each element's name with its instance number so overlay items stay distinguishable
 * (e.g. selecting "[2] Grey Matter" in the overlay list). A single-instance run produces
 * unprefixed names, unchanged.</p>
 */
public final class OverlayRenderer {

    /** Stroke width for polylines (Purkinje, fissures). */
    private static final float LINE_STROKE_WIDTH    = 1.5f;
    /** Stroke width for filled area ROI outlines. */
    private static final float AREA_STROKE_WIDTH    = 1.0f;
    /** Alpha for the per-subsection transparent fill (0 = invisible, 255 = opaque). */
    private static final int   SUBSECTION_FILL_ALPHA = 60;

    /** Twelve visually distinct hues for per-subsection fills (cycled if needed). */
    private static final Color[] SUBSECTION_COLORS = {
        new Color(255, 100,  80), // warm red
        new Color(255, 180,  60), // amber
        new Color(180, 255,  80), // lime
        new Color( 60, 210, 120), // teal-green
        new Color( 60, 200, 240), // sky blue
        new Color( 80, 120, 255), // blue
        new Color(180,  80, 255), // purple
        new Color(255,  80, 200), // pink
    };

    private OverlayRenderer() {
    }

    /**
     * Builds the overlay for a single instance and adds it to {@code imp}. Any existing
     * overlay on the image is cleared first. For multiple instances, use {@link #renderAll}
     * instead — calling this repeatedly would overwrite each previous instance's overlay.
     *
     * @param imp              the image to annotate
     * @param layers           the validated input ROI set (provides the Purkinje line and original outlines)
     * @param geo              the intermediate geometry produced by {@link org.cerebellum.morphometry.measurement.MeasurementEngine#buildGeometry}
     * @param colorSubsections whether to add distinct semitransparent fills for each lobule
     */
    public static void render(ImagePlus imp, LayerSet layers, IntermediateGeometry geo,
                               boolean colorSubsections) {
        Overlay overlay = new Overlay();
        addInstanceTo(overlay, layers, geo, colorSubsections, "");
        imp.setOverlay(overlay);
        imp.updateAndRepaintWindow();
    }

    /**
     * Builds one combined overlay covering every instance and sets it on {@code imp} in a
     * single call. Overlay item names are prefixed with {@code "[N] "} for instance N only
     * when {@code instances} has more than one entry, so single-section runs look identical
     * to before.
     */
    public static void renderAll(ImagePlus imp, SortedMap<Integer, LayerSet> instances,
                                  Map<Integer, IntermediateGeometry> geometryByInstance,
                                  boolean colorSubsections) {
        Overlay overlay = new Overlay();
        boolean multi = instances.size() > 1;
        for (Map.Entry<Integer, LayerSet> entry : instances.entrySet()) {
            int instance = entry.getKey();
            IntermediateGeometry geo = geometryByInstance.get(instance);
            if (geo == null) {
                continue;
            }
            String prefix = multi ? "[" + instance + "] " : "";
            addInstanceTo(overlay, entry.getValue(), geo, colorSubsections, prefix);
        }
        imp.setOverlay(overlay);
        imp.updateAndRepaintWindow();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void addInstanceTo(Overlay overlay, LayerSet layers, IntermediateGeometry geo,
                                       boolean colorSubsections, String namePrefix) {
        // ---- Layer fills and outlines ----
        addAreaRoi(overlay, geo.constructed.getGrey(),     new Color(  0,   0, 255, 60),  Color.BLUE,   namePrefix + "Grey Matter");
        addAreaRoi(overlay, geo.constructed.getGranular(), new Color(  0, 200,   0, 60),  Color.GREEN,  namePrefix + "Granular Layer");
        addAreaRoi(overlay, geo.constructed.getMolecular(),new Color(255, 230,   0, 60),  Color.YELLOW, namePrefix + "Molecular Layer");

        // ---- Purkinje line ----
        Roi purkinje = named(layers.getPurkinje(), namePrefix + "Purkinje");
        purkinje.setStrokeColor(Color.RED);
        purkinje.setStrokeWidth(LINE_STROKE_WIDTH);
        overlay.add(purkinje);

        // ---- Partition cut-lines ----
        int fIdx = 1;
        for (Roi cutLine : geo.partitionSet.getExtendedCutLines()) {
            // Clone the polyline directly (preserving its POLYLINE type) rather than wrapping it
            // in a ShapeRoi, so the cut renders as the open line it is.
            Roi r = (Roi) cutLine.clone();
            r.setName(namePrefix + "Fissure" + fIdx++);
            r.setStrokeColor(Color.WHITE);
            r.setStrokeWidth(LINE_STROKE_WIDTH);
            overlay.add(r);
        }

        // ---- Per-subsection fills (optional) ----
        if (colorSubsections) {
            int pIdx = 0;
            for (PartitionSet.Partition p : geo.partitionSet.getPartitions()) {
                Color baseColor = SUBSECTION_COLORS[pIdx % SUBSECTION_COLORS.length];
                Color fill = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(),
                        SUBSECTION_FILL_ALPHA);
                addAreaRoi(overlay, p.getRegion(), fill, null, namePrefix + p.getLabel());
                pIdx++;
            }
        }
    }

    private static void addAreaRoi(Overlay overlay, ShapeRoi shape, Color fill, Color stroke, String name) {
        if (shape == null) {
            return;
        }
        ShapeRoi r = new ShapeRoi(shape);
        r.setName(name);
        if (fill != null) {
            r.setFillColor(fill);
        }
        if (stroke != null) {
            r.setStrokeColor(stroke);
            r.setStrokeWidth(AREA_STROKE_WIDTH);
        }
        overlay.add(r);
    }

    private static Roi named(Roi src, String name) {
        // Clone by wrapping in a ShapeRoi to avoid mutating the source.
        ShapeRoi clone = new ShapeRoi(src);
        clone.setName(name);
        return clone;
    }
}
