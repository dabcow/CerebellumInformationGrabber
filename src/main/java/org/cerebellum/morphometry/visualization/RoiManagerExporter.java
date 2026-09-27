package org.cerebellum.morphometry.visualization;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.cerebellum.morphometry.PluginOutput;
import org.cerebellum.morphometry.geometry.BooleanROIProcessor;
import org.cerebellum.morphometry.geometry.PartitionClipper;
import org.cerebellum.morphometry.geometry.PurkinjeLengthCalculator;
import org.cerebellum.morphometry.measurement.MeasurementEngine.IntermediateGeometry;
import org.cerebellum.morphometry.model.InstanceResult;
import org.cerebellum.morphometry.model.PartitionSet;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds a named {@link Roi} to the ROI Manager for every region the plugin measures, so each can
 * be independently re-selected, inspected, re-measured, or exported like any other FIJI ROI.
 *
 * <p>This mirrors {@link org.cerebellum.morphometry.measurement.MeasurementEngine}'s output:</p>
 * <ul>
 *   <li>the three whole-cerebellum layers ({@code Grey Matter}, {@code Granular Layer},
 *       {@code Molecular Layer});</li>
 *   <li>for each resolved subsection: {@code <label>_Granular}, {@code <label>_Molecular} and
 *       {@code <label>_Purkinje}. A subsection that owns two separate stretches of the Purkinje
 *       line (the wrap-around section of a closed ring) gets {@code <label>_Purkinje_1} and
 *       {@code <label>_Purkinje_2}, which together measure exactly the reported length.</li>
 * </ul>
 *
 * <p>With several instances every name is prefixed with {@code "[N] "}. Every ROI added is an
 * independent copy, marked with {@link PluginOutput} so later runs don't mistake it for input.</p>
 */
public final class RoiManagerExporter {

    private RoiManagerExporter() {
    }

    /** Adds the measurement ROIs of every instance to {@code rm}. */
    public static void addMeasurementRois(RoiManager rm, List<InstanceResult> instances) {
        for (Roi roi : measurementRois(instances)) {
            rm.addRoi(roi);
        }
    }

    /** The ROIs {@link #addMeasurementRois} would add, in order. */
    public static List<Roi> measurementRois(List<InstanceResult> instances) {
        List<Roi> out = new ArrayList<>();
        boolean multi = instances.size() > 1;
        for (InstanceResult inst : instances) {
            String prefix = multi ? "[" + inst.getInstanceNumber() + "] " : "";
            IntermediateGeometry geo = inst.getGeometry();

            addNamed(out, geo.constructed.getGrey(), prefix + "Grey Matter");
            addNamed(out, geo.constructed.getGranular(), prefix + "Granular Layer");
            addNamed(out, geo.constructed.getMolecular(), prefix + "Molecular Layer");

            List<PartitionSet.Partition> partitions = geo.partitionSet.getPartitions();
            for (int i = 0; i < geo.clipped.size(); i++) {
                PartitionClipper.ClippedPartition cp = geo.clipped.get(i);
                addNamed(out, cp.granular, prefix + cp.label + "_Granular");
                addNamed(out, cp.molecular, prefix + cp.label + "_Molecular");

                List<PolygonRoi> segments = PurkinjeLengthCalculator.segmentsInside(
                        inst.getLayers().getPurkinje(), partitions.get(i).getRegion());
                for (int s = 0; s < segments.size(); s++) {
                    String suffix = segments.size() == 1 ? "_Purkinje" : "_Purkinje_" + (s + 1);
                    addNamed(out, segments.get(s), prefix + cp.label + suffix);
                }
            }
        }
        return out;
    }

    /**
     * Copies {@code roi}, names and marks the copy, and appends it — skipping empty shapes. Area
     * shapes are re-traced into a clean outline (same pixels, same measured area) so they don't
     * show Java2D's internal slab edges when selected.
     */
    private static void addNamed(List<Roi> out, Roi roi, String name) {
        if (roi == null) {
            return;
        }
        Rectangle bounds = roi.getBounds();
        if (bounds.width <= 0 && bounds.height <= 0) {
            return; // nothing to show for this measurement (e.g. a layer absent in this partition)
        }
        Roi shown = roi.isArea() ? BooleanROIProcessor.cleanOutline(roi) : roi;
        if (shown != null) {
            out.add(PluginOutput.markedCopy(shown, name));
        }
    }
}
