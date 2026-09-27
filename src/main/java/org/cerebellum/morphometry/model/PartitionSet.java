package org.cerebellum.morphometry.model;

import ij.gui.PolygonRoi;
import ij.gui.ShapeRoi;

import java.util.Collections;
import java.util.List;

/**
 * Output of {@link org.cerebellum.morphometry.geometry.FissurePartitioner}: the cerebellum
 * cut into {@code N+1} lobule-sized subsections by the {@code N} traced fissure lines (7
 * fissures &rarr; 8 subsections in the standard scheme, but any count is supported), plus
 * the extended cutting lines themselves so {@link org.cerebellum.morphometry.visualization.OverlayRenderer}
 * can draw the partition boundaries.
 */
public final class PartitionSet {

    /** One fissure-bounded subsection (e.g. "2Cb", or "Section 3" for a non-standard fissure count). */
    public static final class Partition {
        private final String label;
        private final ShapeRoi region;
        /**
         * Arc-length position of this partition's two ends along the Purkinje polyline, in
         * uncalibrated pixel-space units (consistent with how {@link org.cerebellum.morphometry.geometry.GeometryUtils}
         * measures everything). Convert to physical length via
         * {@link org.cerebellum.morphometry.geometry.PurkinjeLengthCalculator#lengthBetween}.
         */
        private final double purkinjeArcStart;
        private final double purkinjeArcEnd;

        public Partition(String label, ShapeRoi region, double purkinjeArcStart, double purkinjeArcEnd) {
            this.label = label;
            this.region = region;
            this.purkinjeArcStart = purkinjeArcStart;
            this.purkinjeArcEnd = purkinjeArcEnd;
        }

        public String getLabel() {
            return label;
        }

        public ShapeRoi getRegion() {
            return region;
        }

        public double getPurkinjeArcStart() {
            return purkinjeArcStart;
        }

        public double getPurkinjeArcEnd() {
            return purkinjeArcEnd;
        }

        public double getPixelArcLength() {
            return purkinjeArcEnd - purkinjeArcStart;
        }
    }

    private final List<Partition> partitions; // one per resolved lobule, in anatomical/arc order
    private final List<PolygonRoi> extendedCutLines; // size 7, for overlay only

    public PartitionSet(List<Partition> partitions, List<PolygonRoi> extendedCutLines) {
        this.partitions = Collections.unmodifiableList(partitions);
        this.extendedCutLines = Collections.unmodifiableList(extendedCutLines);
    }

    public List<Partition> getPartitions() {
        return partitions;
    }

    /** The seven extended fissure cutting curves (pial surface through the white matter and out the far side). */
    public List<PolygonRoi> getExtendedCutLines() {
        return extendedCutLines;
    }
}
