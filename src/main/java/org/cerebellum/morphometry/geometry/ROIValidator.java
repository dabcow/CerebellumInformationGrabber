package org.cerebellum.morphometry.geometry;

import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.PluginOutput;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.ValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Checks that the ROI Manager contains what the plugin needs and turns the ROIs into one
 * {@link LayerSet} per detected <b>instance</b>. Every problem found is collected and reported
 * together, so the user can fix everything in one pass.
 *
 * <h2>ROI naming &mdash; full names and abbreviations</h2>
 * Matching is case-insensitive. Both long-form names and common lab abbreviations are
 * recognised. Abbreviations are matched as <em>whole tokens</em> (surrounded by
 * non-alphanumeric characters or at the start/end of the name) so that, for example,
 * "cb" matches a ROI named "CB" or "CB_left" but not an unrelated word that happens to
 * contain "cb".
 *
 * <table border="1">
 *   <caption>Accepted ROI names</caption>
 *   <tr><th>ROI</th><th>Accepted names (examples)</th></tr>
 *   <tr><td>Cerebellum</td>
 *       <td>cerebellum, Cerebellum, <b>CB</b>, cb</td></tr>
 *   <tr><td>Granular Layer + White Matter</td>
 *       <td>granular, Granular+WM, GranularWM, <b>GL+WM</b>, GL_WM, GL WM, GLWM,
 *           gl+wm, GL (when WM is also present in the name)</td></tr>
 *   <tr><td>White Matter</td>
 *       <td>WhiteMatter, white matter, <b>WM</b>, wm</td></tr>
 *   <tr><td>Purkinje line</td>
 *       <td>purkinje, Purkinje, <b>PL</b>, pl</td></tr>
 *   <tr><td>Fissure lines (&ge;1)</td>
 *       <td>fissure1, fissure2, &hellip;, Fissure_1, <b>FL1, FL2, &hellip;</b>, fl1, FL_1, FL-2, FL</td></tr>
 * </table>
 *
 * <p><b>Disambiguation priority</b> (important for abbreviations that could match more
 * than one category): Fissure &gt; Purkinje &gt; Granular+WM &gt; Cerebellum &gt; White Matter.
 * In particular, Granular+WM is tested before White Matter, so a ROI named "GL+WM" is
 * correctly identified as Granular+WM even though it also contains the "wm" token.</p>
 *
 * <p>ROIs this plugin added itself (its measurement ROIs, see {@link PluginOutput}) are never
 * treated as input, so the plugin can be re-run on the same ROI Manager.</p>
 *
 * <h2>Line ROIs</h2>
 * <p>The Purkinje line and fissures may be traced with the Segmented Line, Freehand Line or
 * straight Line tool. A straight line is converted to a two-point polyline.</p>
 *
 * <h2>Multiple instances (separately-traced pieces)</h2>
 * <p>When the tissue can't be traced as one outline (a piece broke off, or the cut captured
 * disconnected islands), each piece can be traced as its own <b>instance</b>, identified by a
 * leading number on every ROI name belonging to it &mdash; e.g. {@code "2CB"} and
 * {@code "3GL+WM"}. Names with no leading number belong to instance 1, so single-section
 * workflows need no changes. A fissure's own index follows the instance prefix: {@code "2FL1"} is
 * instance 2's first fissure. Every instance needs its own Cerebellum, Granular+WM and Purkinje
 * ROIs; instances are measured separately and then pooled into one set of results (see {@link
 * org.cerebellum.morphometry.measurement.MeasurementEngine#combine}).</p>
 *
 * <p><b>Naming caution:</b> because a leading number is meaningful, avoid names like "2Cb" or
 * "10Cb" for anything other than this instance-prefix feature &mdash; they mean "the Cerebellum
 * ROI for instance 2/10". Leading numbers longer than {@value #MAX_INSTANCE_DIGITS} digits (e.g.
 * a date) are not treated as instance numbers.</p>
 *
 * <h2>White Matter and fissures are optional</h2>
 * <ul>
 *   <li><b>No White Matter traced?</b> Fine &mdash; some sections contain no white-matter core
 *       (e.g. a peripheral cut through cortex only). That instance's Grey Matter and Granular
 *       Layer numbers simply won't have White Matter excluded, and it isn't split into
 *       subsections. Any fissures traced anyway are ignored (with a note).</li>
 *   <li><b>No fissures traced (but White Matter present)?</b> That instance just isn't split
 *       into subsections. Required for instance 1 when it has White Matter; optional for any
 *       other instance, which may be traced only for its overall extent.</li>
 *   <li><b>Both traced?</b> Full behaviour &mdash; layer breakdown and lobule partitioning.</li>
 * </ul>
 */
public final class ROIValidator {

    /** How far a containment check may be off, as a fraction of area. */
    private static final double CONTAINMENT_TOLERANCE_FRACTION = 0.005; // 0.5 %

    /** Longer leading digit runs (dates, sample IDs) are not instance numbers. */
    static final int MAX_INSTANCE_DIGITS = 4;

    /** At most this many ignored ROI names are listed in the "ignored" note. */
    private static final int MAX_IGNORED_NAMES_LISTED = 10;

    private ROIValidator() {
    }

    // -----------------------------------------------------------------------
    // Public entry points
    // -----------------------------------------------------------------------

    /**
     * Validates the ROI Manager's contents and returns one {@link LayerSet} per detected
     * instance, keyed by instance number and sorted in ascending order. In the common case
     * (no instance-prefixed names present) this map has a single entry under key 1.
     */
    public static SortedMap<Integer, LayerSet> validate(RoiManager rm, ImagePlus imp, Diagnostics diag)
            throws ValidationException {
        if (rm == null || rm.getCount() == 0) {
            throw new ValidationException(List.of(
                    "The ROI Manager is empty. Add the Cerebellum (or CB), Granular+WM (or GL+WM), and "
                    + "Purkinje line (or PL) before running this plugin. White Matter (or WM) and Fissure "
                    + "lines (FL1, FL2, …) are optional — trace them to exclude white matter and to split "
                    + "the section into lobules."));
        }
        return validate(rm.getRoisAsArray(), imp, diag);
    }

    /**
     * Core validation logic, operating on a plain ROI array rather than a live {@link RoiManager}
     * (which cannot exist in a headless test).
     */
    public static SortedMap<Integer, LayerSet> validate(Roi[] rois, ImagePlus imp, Diagnostics diag)
            throws ValidationException {
        List<String> problems = new ArrayList<>();

        if (imp == null) {
            problems.add("No active image. Open the Nissl-stained section this plugin should measure.");
            throw new ValidationException(problems);
        }

        // -------------------------------------------------------------------
        // Group ROIs by instance number, classifying each by name (with its
        // leading-number instance prefix stripped first).
        // -------------------------------------------------------------------
        SortedMap<Integer, InstanceBucket> buckets = new TreeMap<>();
        List<String> ignored = new ArrayList<>();
        int skippedOutputs = 0;
        for (Roi roi : rois) {
            if (roi == null) {
                continue;
            }
            if (PluginOutput.isOutput(roi)) {
                skippedOutputs++;
                continue;
            }
            String name = roi.getName() == null ? "" : roi.getName();
            Classification c = classify(name.toLowerCase(Locale.ROOT));
            if (c == null) {
                ignored.add(name.isEmpty() ? "(unnamed)" : name);
                continue;
            }
            InstanceBucket bucket = buckets.computeIfAbsent(c.instance, k -> new InstanceBucket());
            switch (c.category) {
                case FISSURE:
                    bucket.fissures.add(roi);
                    break;
                case PURKINJE:
                    bucket.purkinje = assign(bucket.purkinje, roi, "Purkinje", bucket.duplicates);
                    break;
                case GRANULAR_WM:
                    bucket.granularWM = assign(bucket.granularWM, roi, "Granular+WM", bucket.duplicates);
                    break;
                case CEREBELLUM:
                    bucket.cerebellum = assign(bucket.cerebellum, roi, "Cerebellum", bucket.duplicates);
                    break;
                case WHITE_MATTER:
                    bucket.whiteMatter = assign(bucket.whiteMatter, roi, "White Matter", bucket.duplicates);
                    break;
                default:
                    throw new IllegalStateException("Unhandled category " + c.category);
            }
        }

        if (skippedOutputs > 0) {
            diag.note("Skipped " + skippedOutputs + " measurement ROI(s) added by an earlier run of this plugin.");
        }
        if (!ignored.isEmpty()) {
            List<String> shown = ignored.subList(0, Math.min(ignored.size(), MAX_IGNORED_NAMES_LISTED));
            diag.note("Ignored " + ignored.size() + " ROI(s) whose names don't match any expected layer: "
                    + String.join(", ", shown) + (ignored.size() > shown.size() ? ", …" : "") + ".");
        }

        if (buckets.isEmpty()) {
            problems.add("No ROIs with recognized names were found. Add the Cerebellum (or CB), "
                    + "Granular+WM (or GL+WM), and Purkinje line (or PL) before running this plugin. "
                    + "White Matter (or WM) and Fissure lines (FL1, FL2, …) are optional.");
            throw new ValidationException(problems);
        }

        boolean multiInstance = buckets.size() > 1;

        // -------------------------------------------------------------------
        // Validate each instance independently, prefixing messages with the
        // instance number only when there's genuinely more than one.
        // -------------------------------------------------------------------
        SortedMap<Integer, LayerSet> result = new TreeMap<>();
        for (Map.Entry<Integer, InstanceBucket> entry : buckets.entrySet()) {
            int instance = entry.getKey();
            InstanceBucket b = entry.getValue();
            String prefix = multiInstance ? "[Instance " + instance + "] " : "";
            String prefixHint = multiInstance ? " (with the \"" + instance + "\" prefix for this instance)" : "";
            boolean isSecondary = instance != 1;
            int problemsBefore = problems.size();

            for (String dup : b.duplicates) {
                problems.add(prefix + "More than one ROI matches " + dup + " — rename or remove one.");
            }

            if (b.cerebellum == null) {
                problems.add(prefix + "Missing the Cerebellum ROI. Add a closed polygon whose name contains "
                        + "\"cerebellum\" or the abbreviation \"CB\"" + prefixHint + ".");
            } else if (!b.cerebellum.isArea()) {
                problems.add(prefix + "The Cerebellum ROI (\"" + b.cerebellum.getName()
                        + "\") is not a closed area. Retrace it as a closed polygon.");
            }

            if (b.granularWM == null) {
                problems.add(prefix + "Missing the Granular+WM ROI. Add a closed polygon whose name contains "
                        + "\"granular\", \"GL+WM\", \"GLWM\", or similar" + prefixHint + ".");
            } else if (!b.granularWM.isArea()) {
                problems.add(prefix + "The Granular+WM ROI (\"" + b.granularWM.getName()
                        + "\") is not a closed area. Retrace it as a closed polygon.");
            }

            if (b.whiteMatter != null && !b.whiteMatter.isArea()) {
                problems.add(prefix + "The White Matter ROI (\"" + b.whiteMatter.getName()
                        + "\") is not a closed area. Retrace it as a closed polygon.");
            }

            PolygonRoi purkinje = null;
            if (b.purkinje == null) {
                problems.add(prefix + "Missing the Purkinje ROI. Add an open line whose name contains "
                        + "\"purkinje\" or the abbreviation \"PL\"" + prefixHint + ".");
            } else if (!b.purkinje.isLine()) {
                problems.add(prefix + "The Purkinje ROI (\"" + b.purkinje.getName()
                        + "\") is a closed area, not a line. Retrace it with the Segmented Line tool.");
            } else {
                purkinje = asPolyline(b.purkinje);
                if (purkinje == null) {
                    problems.add(prefix + "The Purkinje ROI (\"" + b.purkinje.getName()
                            + "\") could not be read as a line. Retrace it with the Segmented Line tool.");
                }
            }

            // Fissures are required only for a primary instance that HAS White Matter; a section
            // with no white-matter core isn't split into lobules, so its fissures are discarded.
            boolean fissuresRequired = !isSecondary && b.whiteMatter != null;
            List<PolygonRoi> fissurePolylines = new ArrayList<>();
            if (b.whiteMatter == null) {
                if (!b.fissures.isEmpty()) {
                    diag.note(prefix + b.fissures.size() + " fissure ROI(s) found but ignored, since there is "
                            + "no White Matter ROI (a section with no white-matter core isn't split into "
                            + "lobules). Trace White Matter if you want it split into subsections.");
                } else {
                    diag.note(prefix + "No White Matter ROI, so this section won't be split into subsections, "
                            + "and its Grey Matter / Granular Layer numbers won't have White Matter excluded.");
                }
            } else if (b.fissures.isEmpty()) {
                if (fissuresRequired) {
                    problems.add(prefix + "No fissure ROIs found. Add at least one open line named "
                            + "\"fissure\" or \"FL1\", \"FL2\", … (see the README for how many you need).");
                } else {
                    diag.note(prefix + "No fissures traced, so this piece won't be split into subsections "
                            + "(its whole-cerebellum totals are still measured).");
                }
            } else {
                for (Roi f : b.fissures) {
                    if (!f.isLine()) {
                        problems.add(prefix + "Fissure ROI \"" + f.getName()
                                + "\" is a closed area, not a line. Retrace it with the Segmented Line tool.");
                        continue;
                    }
                    PolygonRoi polyline = asPolyline(f);
                    if (polyline == null) {
                        problems.add(prefix + "Fissure ROI \"" + f.getName() + "\" could not be read as a line.");
                    } else {
                        fissurePolylines.add(polyline);
                    }
                }
            }

            if (problems.size() > problemsBefore) {
                continue; // this instance has problems; skip the (informational) containment checks
            }

            // Containment checks are informational, not blocking: White Matter or Granular+WM is
            // allowed to reach or extend past its "parent" outline. At the peduncle this is
            // anatomically correct, and it is how the ring of grey matter is deliberately pinched
            // open so the first and last lobules separate (README: "Closing the loop"). A large
            // percentage is still worth a note, since it can also mean swapped or mis-traced ROIs.
            noteOutside(b.granularWM, b.cerebellum, imp, prefix, "Granular+WM", "Cerebellum", diag);
            if (b.whiteMatter != null) {
                noteOutside(b.whiteMatter, b.granularWM, imp, prefix, "White Matter", "Granular+WM", diag);
            }

            result.put(instance, new LayerSet(b.cerebellum, b.granularWM, b.whiteMatter, purkinje, fissurePolylines));
        }

        if (!problems.isEmpty()) {
            if (multiInstance) {
                problems.add(0, "Detected " + buckets.size() + " instances from ROI name prefixes "
                        + buckets.keySet() + " (see \"Multiple instances\" in the README). If you only meant "
                        + "to trace one section, a ROI name probably starts with a digit by accident — "
                        + "check for that instead of tracing another instance.");
            }
            throw new ValidationException(problems);
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Instance grouping
    // -----------------------------------------------------------------------

    private enum Category { FISSURE, PURKINJE, GRANULAR_WM, CEREBELLUM, WHITE_MATTER }

    private static final class Classification {
        final Category category;
        final int instance;

        Classification(Category category, int instance) {
            this.category = category;
            this.instance = instance;
        }
    }

    private static final class InstanceBucket {
        Roi cerebellum;
        Roi granularWM;
        Roi whiteMatter;
        Roi purkinje;
        final List<Roi> fissures = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();
    }

    /** Records a duplicate when {@code current} is already set; returns the ROI to keep. */
    private static Roi assign(Roi current, Roi candidate, String what, List<String> duplicates) {
        if (current != null) {
            String hint = looksLikeLayerOutput(current) || looksLikeLayerOutput(candidate)
                    ? " (a whole-layer ROI such as \"Granular Layer\" may have been added by an earlier run "
                      + "of this plugin; if so, delete it)"
                    : "";
            duplicates.add(what + " (\"" + current.getName() + "\" and \"" + candidate.getName() + "\")" + hint);
        }
        return candidate;
    }

    private static boolean looksLikeLayerOutput(Roi roi) {
        String name = roi.getName() == null ? "" : roi.getName().trim();
        return name.equalsIgnoreCase("Grey Matter") || name.equalsIgnoreCase("Granular Layer")
                || name.equalsIgnoreCase("Molecular Layer");
    }

    /**
     * Strips an optional leading run of digits from {@code lower} (the instance number &mdash;
     * absent means instance 1) and classifies the remainder using the priority-ordered name
     * matchers. Returns {@code null} if nothing matches, or if the leading number is too long to
     * be an instance number.
     */
    static Classification classify(String lower) {
        int i = 0;
        while (i < lower.length() && Character.isDigit(lower.charAt(i))) {
            i++;
        }
        if (i > MAX_INSTANCE_DIGITS) {
            return null;
        }
        int instance = (i == 0) ? 1 : Integer.parseInt(lower.substring(0, i));
        if (instance == 0) {
            return null; // "0…" is not a valid instance number (instances start at 1)
        }
        String rest = lower.substring(i);

        if (matchesFissure(rest))     return new Classification(Category.FISSURE, instance);
        if (matchesPurkinje(rest))    return new Classification(Category.PURKINJE, instance);
        if (matchesGranularWM(rest))  return new Classification(Category.GRANULAR_WM, instance);
        if (matchesCerebellum(rest))  return new Classification(Category.CEREBELLUM, instance);
        if (matchesWhiteMatter(rest)) return new Classification(Category.WHITE_MATTER, instance);
        return null;
    }

    // -----------------------------------------------------------------------
    // Name-matching predicates (operate on the name AFTER instance-prefix stripping)
    // -----------------------------------------------------------------------

    /** Cerebellum: "cerebellum" anywhere, OR whole-word "cb". */
    private static boolean matchesCerebellum(String lower) {
        return lower.contains("cerebellum") || isWholeWord(lower, "cb");
    }

    /**
     * Granular + White Matter: "granular" anywhere; compound abbreviations
     * GL+WM / GLWM / GL_WM / GL-WM / GL WM; or whole-word "GL" co-occurring
     * with whole-word "WM" anywhere in the same name.
     * This predicate MUST be evaluated before {@link #matchesWhiteMatter} so
     * that a name like "GL+WM" is not misclassified as the White Matter ROI.
     */
    private static boolean matchesGranularWM(String lower) {
        if (lower.contains("granular")) return true;
        if (lower.contains("gl+wm") || lower.contains("glwm")
                || lower.contains("gl_wm") || lower.contains("gl-wm")
                || lower.contains("gl wm")) return true;
        return isWholeWord(lower, "gl") && isWholeWord(lower, "wm");
    }

    /** White Matter: "white" anywhere, OR whole-word "wm". */
    private static boolean matchesWhiteMatter(String lower) {
        return lower.contains("white") || isWholeWord(lower, "wm");
    }

    /** Purkinje: "purkinje" anywhere, OR whole-word "pl" (Purkinje Layer / Purkinje Line). */
    private static boolean matchesPurkinje(String lower) {
        return lower.contains("purkinje") || isWholeWord(lower, "pl");
    }

    /**
     * Fissure: "fissure" anywhere; whole-word "fl"; or "fl" at a word boundary
     * followed by a non-letter (covers FL1, FL2, … FL7, FL_1, FL-2, etc.).
     */
    private static boolean matchesFissure(String lower) {
        if (lower.contains("fissure")) return true;
        if (isWholeWord(lower, "fl"))  return true;
        return matchesFLNumbered(lower);
    }

    /**
     * Returns true when "fl" appears at a word boundary and is immediately
     * followed by a non-letter character (digit, underscore, hyphen, space, …).
     * This matches FL1 / FL_1 / FL-1 / "fl 1" but not words like "flat".
     */
    private static boolean matchesFLNumbered(String lower) {
        int idx = 0;
        while ((idx = lower.indexOf("fl", idx)) >= 0) {
            boolean beforeOk = idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            int afterIdx = idx + 2;
            boolean afterOk = afterIdx < lower.length() && !Character.isLetter(lower.charAt(afterIdx));
            if (beforeOk && afterOk) return true;
            idx++;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Returns true when {@code token} appears in {@code lower} as a complete word — bounded by
     * non-alphanumeric characters or string start/end.
     * Examples: isWholeWord("cb_outline", "cb") → true, isWholeWord("flat", "fl") → false.
     */
    private static boolean isWholeWord(String lower, String token) {
        int idx = 0;
        while ((idx = lower.indexOf(token, idx)) >= 0) {
            boolean beforeOk = idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            boolean afterOk  = (idx + token.length() >= lower.length())
                    || !Character.isLetterOrDigit(lower.charAt(idx + token.length()));
            if (beforeOk && afterOk) return true;
            idx++;
        }
        return false;
    }

    /**
     * A line ROI as an open polyline: polylines and freehand lines as they are, a straight
     * {@link Line} as a two-point polyline. Returns {@code null} for anything else.
     */
    private static PolygonRoi asPolyline(Roi roi) {
        if (roi instanceof PolygonRoi && roi.isLine()) {
            return (PolygonRoi) roi;
        }
        if (roi instanceof Line) {
            Line line = (Line) roi;
            PolygonRoi polyline = new PolygonRoi(
                    new float[] {(float) line.x1d, (float) line.x2d},
                    new float[] {(float) line.y1d, (float) line.y2d}, 2, Roi.POLYLINE);
            polyline.setName(roi.getName());
            return polyline;
        }
        return null;
    }

    /** Logs a note when a noticeable part of {@code inner} lies outside {@code outer}. */
    private static void noteOutside(Roi inner, Roi outer, ImagePlus imp, String prefix,
            String innerName, String outerName, Diagnostics diag) {
        double innerArea = BooleanROIProcessor.area(inner, imp);
        double outside = BooleanROIProcessor.area(BooleanROIProcessor.subtract(inner, outer), imp);
        double tolerance = Math.max(innerArea * CONTAINMENT_TOLERANCE_FRACTION, 1e-9);
        if (outside > tolerance && innerArea > 0) {
            diag.note(prefix + String.format(Locale.ROOT, "~%.1f%%", 100.0 * outside / innerArea)
                    + " of the " + innerName + " ROI's area falls outside the " + outerName + " ROI. This is "
                    + "fine if intentional (e.g. tracing out through the peduncle to separate the first and "
                    + "last lobule); otherwise check that the two ROIs weren't traced on different sections "
                    + "or swapped.");
        }
    }
}
