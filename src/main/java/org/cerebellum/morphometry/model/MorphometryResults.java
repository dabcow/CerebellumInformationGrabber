package org.cerebellum.morphometry.model;

import java.util.Collections;
import java.util.List;

/**
 * The complete set of numbers that go into the output table: whole-cerebellum totals
 * plus the per-subsection (per-lobule) breakdown. Deliberately named {@code MorphometryResults}
 * rather than {@code Measurements}, because {@code ij.measure.Measurements} is already a
 * (very different) interface of bitmask constants in the ImageJ API and re-using the name
 * would shadow it.
 */
public final class MorphometryResults {

    /** One row of the per-subsection part of the table (e.g. the "2Cb" row). */
    public static final class SubsectionResult {
        private final String label;
        private final double granularArea;
        private final double molecularArea;
        private final double purkinjeLength;

        public SubsectionResult(String label, double granularArea, double molecularArea, double purkinjeLength) {
            this.label = label;
            this.granularArea = granularArea;
            this.molecularArea = molecularArea;
            this.purkinjeLength = purkinjeLength;
        }

        public String getLabel() {
            return label;
        }

        public double getGranularArea() {
            return granularArea;
        }

        public double getMolecularArea() {
            return molecularArea;
        }

        public double getPurkinjeLength() {
            return purkinjeLength;
        }
    }

    private final double cerebellumArea;
    private final double greyMatterArea;
    private final double granularLayerArea;
    private final double molecularLayerArea;
    private final double totalPurkinjeLength;
    private final double totalPurkinjeArea;
    private final List<SubsectionResult> subsections; // one per resolved lobule, in anatomical/arc order
    private final String areaUnit;
    private final String lengthUnit;

    public MorphometryResults(double cerebellumArea, double greyMatterArea, double granularLayerArea,
                               double molecularLayerArea, double totalPurkinjeLength, double totalPurkinjeArea,
                               List<SubsectionResult> subsections, String areaUnit, String lengthUnit) {
        this.cerebellumArea = cerebellumArea;
        this.greyMatterArea = greyMatterArea;
        this.granularLayerArea = granularLayerArea;
        this.molecularLayerArea = molecularLayerArea;
        this.totalPurkinjeLength = totalPurkinjeLength;
        this.totalPurkinjeArea = totalPurkinjeArea;
        this.subsections = Collections.unmodifiableList(subsections);
        this.areaUnit = areaUnit;
        this.lengthUnit = lengthUnit;
    }

    public double getCerebellumArea() {
        return cerebellumArea;
    }

    public double getGreyMatterArea() {
        return greyMatterArea;
    }

    public double getGranularLayerArea() {
        return granularLayerArea;
    }

    public double getMolecularLayerArea() {
        return molecularLayerArea;
    }

    public double getTotalPurkinjeLength() {
        return totalPurkinjeLength;
    }

    /** See {@link org.cerebellum.morphometry.geometry.PurkinjeLengthCalculator} for what this is. */
    public double getTotalPurkinjeArea() {
        return totalPurkinjeArea;
    }

    public List<SubsectionResult> getSubsections() {
        return subsections;
    }

    public String getAreaUnit() {
        return areaUnit;
    }

    public String getLengthUnit() {
        return lengthUnit;
    }
}
