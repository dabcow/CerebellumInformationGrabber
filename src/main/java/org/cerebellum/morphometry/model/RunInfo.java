package org.cerebellum.morphometry.model;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileInfo;
import ij.measure.Calibration;
import org.cerebellum.morphometry.BuildInfo;
import org.cerebellum.morphometry.Diagnostics;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Provenance for one run: which plugin version measured which image, with what calibration, how
 * each traced piece was partitioned, and every note and warning raised along the way. Exported
 * alongside the numbers so a results file can always be traced back to how it was produced.
 */
public final class RunInfo {

    private final List<String[]> fields;
    private final List<Diagnostics.Entry> messages;

    public RunInfo(List<String[]> fields, List<Diagnostics.Entry> messages) {
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /** Label/value pairs, in display order. */
    public List<String[]> getFields() {
        return fields;
    }

    public List<Diagnostics.Entry> getMessages() {
        return messages;
    }

    /** Describes a completed run. */
    public static RunInfo describe(ImagePlus imp, List<InstanceResult> instances, Diagnostics diag) {
        List<String[]> f = new ArrayList<>();
        f.add(pair("Plugin", BuildInfo.NAME + " " + BuildInfo.version()));
        f.add(pair("ImageJ", IJ.getVersion() + " (Java " + System.getProperty("java.version") + ")"));
        f.add(pair("Run at", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()));
        f.add(pair("Image", imp.getTitle()));
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi != null && fi.directory != null && fi.fileName != null) {
            f.add(pair("Image file", fi.getFilePath()));
        }
        f.add(pair("Image size", imp.getWidth() + " × " + imp.getHeight() + " pixels"));
        f.add(pair("Pixel size", describeCalibration(imp.getCalibration())));
        f.add(pair("Traced pieces", Integer.toString(instances.size())));
        for (InstanceResult inst : instances) {
            f.add(pair("Piece " + inst.getInstanceNumber(), describePartition(inst)));
        }
        List<Diagnostics.Entry> messages = diag.getEntries();
        f.add(pair("Warnings", Integer.toString(diag.getWarnings().size())));
        return new RunInfo(f, messages);
    }

    private static String describeCalibration(Calibration cal) {
        if (cal == null || !cal.scaled()) {
            return "not calibrated (areas in pixel², lengths in pixels)";
        }
        return String.format(Locale.ROOT, "%s × %s %s", trim(cal.pixelWidth), trim(cal.pixelHeight), cal.getUnit());
    }

    private static String describePartition(InstanceResult inst) {
        int sections = inst.getResults().getSubsections().size();
        LayerSet layers = inst.getLayers();
        if (sections == 0) {
            if (layers != null && layers.getWhiteMatter() == null) {
                return "not split into sections (no White Matter ROI)";
            }
            return "not split into sections (no fissures traced)";
        }
        PartitionSet ps = inst.getGeometry().partitionSet;
        String fissures = layers == null ? "" : layers.getFissures().size() + " fissure(s), ";
        String topology = ps.isRing()
                ? "grey matter is a closed ring"
                : "grey matter pinched open at the peduncle";
        String joined = ps.areEndsJoined() ? "; first and last lobules joined" : "";
        return fissures + sections + " section(s); " + topology + joined;
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String[] pair(String label, String value) {
        return new String[] {label, value};
    }
}
