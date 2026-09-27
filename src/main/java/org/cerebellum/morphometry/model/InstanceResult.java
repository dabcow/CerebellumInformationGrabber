package org.cerebellum.morphometry.model;

/**
 * Pairs an instance number (see {@link org.cerebellum.morphometry.geometry.ROIValidator}'s
 * "Multiple instances" section) with the {@link MorphometryResults} measured for it. A
 * single-section run produces exactly one of these, under instance number 1.
 */
public final class InstanceResult {

    private final int instanceNumber;
    private final MorphometryResults results;

    public InstanceResult(int instanceNumber, MorphometryResults results) {
        this.instanceNumber = instanceNumber;
        this.results = results;
    }

    public int getInstanceNumber() {
        return instanceNumber;
    }

    public MorphometryResults getResults() {
        return results;
    }
}
