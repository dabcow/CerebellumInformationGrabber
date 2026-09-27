package org.cerebellum.morphometry.model;

import org.cerebellum.morphometry.measurement.MeasurementEngine.IntermediateGeometry;

/**
 * Everything computed for one instance (see {@link org.cerebellum.morphometry.geometry.ROIValidator}'s
 * "Multiple instances" section): its validated input ROIs, the geometry built from them, and the
 * numbers measured from that geometry. A single-section run produces exactly one of these, under
 * instance number 1.
 */
public final class InstanceResult {

    private final int instanceNumber;
    private final LayerSet layers;
    private final IntermediateGeometry geometry;
    private final MorphometryResults results;

    public InstanceResult(int instanceNumber, LayerSet layers, IntermediateGeometry geometry,
                          MorphometryResults results) {
        this.instanceNumber = instanceNumber;
        this.layers = layers;
        this.geometry = geometry;
        this.results = results;
    }

    public int getInstanceNumber() {
        return instanceNumber;
    }

    public LayerSet getLayers() {
        return layers;
    }

    public IntermediateGeometry getGeometry() {
        return geometry;
    }

    public MorphometryResults getResults() {
        return results;
    }
}
