package org.cerebellum.morphometry.model;

import ij.ImagePlus;
import org.cerebellum.morphometry.BuildInfo;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.geometry.ROIValidator;
import org.cerebellum.morphometry.measurement.MeasurementEngine;
import org.cerebellum.morphometry.testing.SyntheticSection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunInfoTest {

    @Test
    void recordsWhatProducedTheResults() throws Exception {
        SyntheticSection s = SyntheticSection.builder().calibration(0.5, "um").build();
        ImagePlus imp = s.image();
        Diagnostics diag = Diagnostics.silent();
        List<InstanceResult> results = MeasurementEngine.analyze(ROIValidator.validate(s.rois(), imp, diag), imp, diag);

        RunInfo info = RunInfo.describe(imp, results, diag);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String[] f : info.getFields()) {
            fields.put(f[0], f[1]);
        }

        assertFalse(BuildInfo.version().equals("development"), "the build should stamp the version");
        assertEquals(BuildInfo.NAME + " " + BuildInfo.version(), fields.get("Plugin"));
        assertEquals("1200 × 1000 pixels", fields.get("Image size"));
        assertEquals("0.5 × 0.5 µm", fields.get("Pixel size"));
        assertEquals("7 fissure(s), 8 section(s); grey matter pinched open at the peduncle", fields.get("Piece 1"));
        assertEquals("0", fields.get("Warnings"));
        assertTrue(fields.containsKey("Run at"));
        assertEquals(diag.getEntries().size(), info.getMessages().size());
    }
}
