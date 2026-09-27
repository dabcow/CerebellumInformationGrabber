package org.cerebellum.morphometry.visualization;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.gui.TextRoi;
import org.cerebellum.morphometry.PluginOutput;
import org.cerebellum.morphometry.geometry.BooleanROIProcessor;
import org.cerebellum.morphometry.measurement.MeasurementEngine.IntermediateGeometry;
import org.cerebellum.morphometry.model.InstanceResult;
import org.cerebellum.morphometry.model.PartitionSet;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Adds a non-destructive vector overlay to the image showing each computed layer:
 *
 * <ul>
 *   <li>Grey Matter: blue</li>
 *   <li>Granular Layer: green</li>
 *   <li>Molecular Layer: yellow</li>
 *   <li>Purkinje line: red</li>
 *   <li>Partition cut-lines (the fissures, extended to span the grey matter): white</li>
 * </ul>
 *
 * <p>Optionally, each lobule also gets a distinct semitransparent fill and a text label with its
 * name, making it easy to check that the partition matches the anatomy.</p>
 *
 * <p>Re-running the plugin replaces only the items it added before; anything else already on the
 * image's overlay (a scale bar, the user's own annotations) is kept. With several instances,
 * item names are prefixed with the instance number (e.g. "[2] Grey Matter").</p>
 */
public final class OverlayRenderer {

    /** Stroke width for polylines (Purkinje, fissures). */
    private static final float LINE_STROKE_WIDTH    = 1.5f;
    /** Stroke width for filled area ROI outlines. */
    private static final float AREA_STROKE_WIDTH    = 1.0f;
    /** Alpha for layer and per-subsection fills (0 = invisible, 255 = opaque). */
    private static final int   FILL_ALPHA           = 60;

    /** Twelve visually distinct hues for per-subsection fills (cycled if there are more subsections). */
    private static final Color[] SUBSECTION_COLORS = {
        new Color(255, 100,  80), // warm red
        new Color(255, 180,  60), // amber
        new Color(180, 255,  80), // lime
        new Color( 60, 210, 120), // green
        new Color( 60, 200, 240), // sky blue
        new Color( 80, 120, 255), // blue
        new Color(180,  80, 255), // purple
        new Color(255,  80, 200), // pink
        new Color(255, 230,  90), // yellow
        new Color( 40, 170, 170), // teal
        new Color(200, 140,  90), // tan
        new Color(150, 150, 255), // lavender
    };

    /** Overlay item names written by version 1.0.0, which did not tag its items. */
    private static final Pattern LEGACY_ITEM_NAME = Pattern.compile(
            "^(\\[\\d+\\] )?(Grey Matter|Granular Layer|Molecular Layer|Purkinje|Fissure\\d+"
            + "|\\d+(/\\d+)?Cb|Section \\d+)$");

    private OverlayRenderer() {
    }

    /** Builds the combined overlay for every instance and sets it on {@code imp}. */
    public static void render(ImagePlus imp, List<InstanceResult> instances, boolean subsectionFills) {
        imp.setOverlay(buildOverlay(imp.getOverlay(), instances, subsectionFills, labelFontSize(imp)));
        imp.updateAndRepaintWindow();
    }

    /**
     * The overlay to show: every item of {@code existing} that this plugin didn't add, followed by
     * a fresh set of items for {@code instances}. {@code existing} itself is not modified.
     */
    public static Overlay buildOverlay(Overlay existing, List<InstanceResult> instances,
                                       boolean subsectionFills, int labelFontSize) {
        Overlay overlay = new Overlay();
        if (existing != null) {
            for (Roi item : existing.toArray()) {
                if (!isOurs(item)) {
                    overlay.add(item);
                }
            }
        }
        boolean multi = instances.size() > 1;
        for (InstanceResult inst : instances) {
            String prefix = multi ? "[" + inst.getInstanceNumber() + "] " : "";
            addInstance(overlay, inst, subsectionFills, prefix, labelFontSize);
        }
        return overlay;
    }

    /** A label size readable at typical zoom: about 1/40 of the image's shorter side. */
    static int labelFontSize(ImagePlus imp) {
        return Math.max(12, (int) Math.round(Math.min(imp.getWidth(), imp.getHeight()) / 40.0));
    }

    private static boolean isOurs(Roi item) {
        if (PluginOutput.isOutput(item)) {
            return true;
        }
        String name = item.getName();
        return name != null && LEGACY_ITEM_NAME.matcher(name).matches();
    }

    private static void addInstance(Overlay overlay, InstanceResult inst, boolean subsectionFills,
                                    String prefix, int labelFontSize) {
        IntermediateGeometry geo = inst.getGeometry();

        addArea(overlay, geo.constructed.getGrey(),      withAlpha(Color.BLUE),                Color.BLUE,   prefix + "Grey Matter");
        addArea(overlay, geo.constructed.getGranular(),  withAlpha(new Color(0, 200, 0)),      Color.GREEN,  prefix + "Granular Layer");
        addArea(overlay, geo.constructed.getMolecular(), withAlpha(new Color(255, 230, 0)),    Color.YELLOW, prefix + "Molecular Layer");

        // Copy the polyline as a polyline rather than wrapping it in a ShapeRoi: ShapeRoi converts
        // a line into a thin *area*, which draws as a pixel-staircase outline instead of a line.
        Roi purkinje = PluginOutput.markedCopy(inst.getLayers().getPurkinje(), prefix + "Purkinje");
        purkinje.setStrokeColor(Color.RED);
        purkinje.setStrokeWidth(LINE_STROKE_WIDTH);
        overlay.add(purkinje);

        int fIdx = 1;
        for (Roi cutLine : geo.partitionSet.getCutLines()) {
            Roi r = PluginOutput.markedCopy(cutLine, prefix + "Fissure" + fIdx++);
            r.setStrokeColor(Color.WHITE);
            r.setStrokeWidth(LINE_STROKE_WIDTH);
            overlay.add(r);
        }

        if (subsectionFills) {
            List<PartitionSet.Partition> partitions = geo.partitionSet.getPartitions();
            for (int i = 0; i < partitions.size(); i++) {
                PartitionSet.Partition p = partitions.get(i);
                Color fill = withAlpha(SUBSECTION_COLORS[i % SUBSECTION_COLORS.length]);
                addArea(overlay, p.getRegion(), fill, null, prefix + p.getLabel());
                addLabel(overlay, p, prefix, labelFontSize);
            }
        }
    }

    private static void addLabel(Overlay overlay, PartitionSet.Partition p, String prefix, int fontSize) {
        Point2D.Double anchor = p.getLabelAnchor();
        if (anchor == null) {
            return;
        }
        TextRoi text = new TextRoi(anchor.x, anchor.y, p.getLabel(), new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        text.setAntiAlias(true);
        text.setStrokeColor(Color.WHITE);
        text.setFillColor(new Color(0, 0, 0, 140));
        Rectangle b = text.getBounds();
        text.setLocation(anchor.x - b.width / 2.0, anchor.y - b.height / 2.0);
        text.setName(prefix + p.getLabel() + " label");
        overlay.add(PluginOutput.mark(text));
    }

    private static void addArea(Overlay overlay, ShapeRoi shape, Color fill, Color stroke, String name) {
        Roi clean = BooleanROIProcessor.cleanOutline(shape);
        if (clean == null) {
            return;
        }
        Roi r = PluginOutput.markedCopy(clean, name);
        if (fill != null) {
            r.setFillColor(fill);
        }
        if (stroke != null) {
            r.setStrokeColor(stroke);
            r.setStrokeWidth(AREA_STROKE_WIDTH);
        }
        overlay.add(r);
    }

    private static Color withAlpha(Color c) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), FILL_ALPHA);
    }
}
