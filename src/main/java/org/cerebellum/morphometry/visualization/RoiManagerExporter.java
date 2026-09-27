package org.cerebellum.morphometry.visualization;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.plugin.frame.RoiManager;
import org.cerebellum.morphometry.geometry.PartitionClipper;
import org.cerebellum.morphometry.geometry.PurkinjeLengthCalculator;
import org.cerebellum.morphometry.measurement.MeasurementEngine.IntermediateGeometry;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.PartitionSet;

import java.awt.Rectangle;
import java.util.List;

/**
 * Adds a named {@link Roi} to the ROI Manager for every number the plugin reports, so each
 * measured region can be independently re-selected, inspected, re-measured, or exported like
 * any other FIJI ROI &mdash; not just read off the results table.
 *
 * <p>This mirrors {@link org.cerebellum.morphometry.measurement.MeasurementEngine}'s numeric
 * output one-to-one:</p>
 * <ul>
 *   <li>the three whole-cerebellum layers (Grey Matter, Granular Layer, Molecular Layer);</li>
 *   <li>for each lobule that was actually resolved (see {@link PartitionSet}, which may be
 *       fewer than eight if a fissure didn't fully cross one of the layers): its clipped
 *       Granular area, clipped Molecular area, and Purkinje sub-segment.</li>
 * </ul>
 *
 * <p>Names use an underscore convention ({@code "<lobule>_Granular"}, {@code
 * "<lobule>_Molecular"}, {@code "<lobule>_Purkinje"}) rather than spaces, so they sort
 * predictably next to each other and stay friendly to downstream scripts that match on ROI
 * name. Every added ROI is a defensive copy &mdash; nothing already in the geometry pipeline
 * (used simultaneously by the overlay renderer and the results table) is mutated.</p>
 *
 * <p>When multiple instances are present (see {@link
 * org.cerebellum.morphometry.geometry.ROIValidator}'s "Multiple instances" section), pass
 * each instance's number to {@link #addMeasurementRois(RoiManager, LayerSet, IntermediateGeometry, int)}
 * so names stay distinguishable across instances (e.g. {@code "[2] 2Cb_Granular"}) instead of
 * colliding — a single-instance run can keep using the simpler overload, which adds no prefix.</p>
 */
public final class RoiManagerExporter {

    private RoiManagerExporter() {
    }

    /** Single-instance convenience overload — adds no name prefix. */
    public static void addMeasurementRois(RoiManager rm, LayerSet layers, IntermediateGeometry geo) {
        addMeasurementRois(rm, layers, geo, "");
    }

    /**
     * Adds one ROI per measurement to {@code rm}, with every name prefixed by
     * {@code "[instanceNumber] "}.
     *
     * @param rm             the ROI Manager to add to (already containing the user's input ROIs)
     * @param layers         validated input ROIs, needed for the whole Purkinje polyline
     * @param geo            intermediate geometry from {@link org.cerebellum.morphometry.measurement.MeasurementEngine#buildGeometry}
     * @param instanceNumber which instance this is, used to keep names distinguishable across instances
     */
    public static void addMeasurementRois(RoiManager rm, LayerSet layers, IntermediateGeometry geo, int instanceNumber) {
        addMeasurementRois(rm, layers, geo, "[" + instanceNumber + "] ");
    }

    /**
     * Adds one ROI per measurement to {@code rm}.
     *
     * @param rm         the ROI Manager to add to (already containing the user's input ROIs)
     * @param layers     validated input ROIs, needed for the whole Purkinje polyline
     * @param geo        intermediate geometry from {@link org.cerebellum.morphometry.measurement.MeasurementEngine#buildGeometry}
     * @param namePrefix prepended to every added ROI's name (empty for a single-instance run)
     */
    private static void addMeasurementRois(RoiManager rm, LayerSet layers, IntermediateGeometry geo, String namePrefix) {
        addNamed(rm, geo.constructed.getGrey(), namePrefix + "Grey Matter");
        addNamed(rm, geo.constructed.getGranular(), namePrefix + "Granular Layer");
        addNamed(rm, geo.constructed.getMolecular(), namePrefix + "Molecular Layer");

        List<PartitionSet.Partition> partitions = geo.partitionSet.getPartitions();
        for (int i = 0; i < geo.clipped.size(); i++) {
            PartitionClipper.ClippedPartition cp = geo.clipped.get(i);
            PartitionSet.Partition p = partitions.get(i);

            addNamed(rm, cp.granular, namePrefix + cp.label + "_Granular");
            addNamed(rm, cp.molecular, namePrefix + cp.label + "_Molecular");

            PolygonRoi purkinjeSegment = PurkinjeLengthCalculator.extractInside(
                    layers.getPurkinje(), p.getRegion());
            addNamed(rm, purkinjeSegment, namePrefix + cp.label + "_Purkinje");
        }
    }

    /** Defensive-copies {@code roi}, names the copy, and adds it — skipping empty/degenerate shapes. */
    private static void addNamed(RoiManager rm, Roi roi, String name) {
        if (roi == null) {
            return;
        }
        Rectangle bounds = roi.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            return; // nothing to show for this measurement (e.g. a layer absent in this partition)
        }
        Roi copy = (roi instanceof ShapeRoi) ? new ShapeRoi(roi) : (Roi) roi.clone();
        copy.setName(name);
        rm.addRoi(copy);
    }
}
