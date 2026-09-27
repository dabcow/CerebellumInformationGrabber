package org.cerebellum.morphometry.model;

import java.util.Collections;
import java.util.List;

/**
 * Thrown by {@link org.cerebellum.morphometry.geometry.ROIValidator} when the ROIs
 * found in the RoiManager do not satisfy the plugin's requirements. Carries every
 * problem found (not just the first one) so the user can fix everything in one pass
 * instead of being told about one missing/malformed ROI at a time.
 */
public class ValidationException extends Exception {

    private final List<String> problems;

    public ValidationException(List<String> problems) {
        super(buildMessage(problems));
        this.problems = Collections.unmodifiableList(problems);
    }

    public List<String> getProblems() {
        return problems;
    }

    private static String buildMessage(List<String> problems) {
        StringBuilder sb = new StringBuilder("ROI validation failed (" + problems.size() + " problem"
                + (problems.size() == 1 ? "" : "s") + "):\n");
        for (String p : problems) {
            sb.append(" \u2022 ").append(p).append('\n');
        }
        return sb.toString();
    }
}
