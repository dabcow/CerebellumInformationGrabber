package org.cerebellum.morphometry.geometry;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.ValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Checks that the RoiManager contains exactly what the plugin needs and turns the ROIs
 * into one {@link LayerSet} per detected <b>instance</b>. Every problem found is collected
 * and reported together.
 *
 * <h2>ROI naming — full names and abbreviations</h2>
 * Matching is case-insensitive. Both long-form names and common lab abbreviations are
 * recognised. Abbreviations are matched as <em>whole tokens</em> (surrounded by
 * non-alphanumeric characters or at the start/end of the name) so that, for example,
 * "cb" matches a ROI named "CB" or "CB_left" but not an unrelated word that happens to
 * contain "cb".
 *
 * <table border="1">
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
 *       <td>fissure1, fissure2, …, Fissure_1, <b>FL1, FL2, …</b>, fl1, fl2, …, FL_1, FL-2, FL</td></tr>
 * </table>
 *
 * <p><b>Disambiguation priority</b> (important for abbreviations that could match more
 * than one category): Fissure &gt; Purkinje &gt; Granular+WM &gt; Cerebellum &gt; White Matter.
 * In particular, Granular+WM is tested before White Matter, so a ROI named "GL+WM" is
 * correctly identified as Granular+WM even though it also contains the "wm" token.</p>
 *
 * <h2>Multiple instances (separately-traced sections)</h2>
 * <p>Sometimes the tissue itself isn't one traceable outline — a piece may have broken
 * off during sectioning, or the cut may capture disconnected islands of cerebellar
 * tissue. Rather than force those into one Cerebellum/Granular+WM hierarchy that doesn't
 * geometrically make sense, each disconnected piece can be traced as its own complete,
 * independent <b>instance</b>: its own Cerebellum, Granular+WM, White Matter, Purkinje
 * line, and fissures, all measured separately and reported as additional rows in the
 * same final table.</p>
 *
 * <p>An instance is identified by a leading number on every ROI name belonging to it —
 * e.g. {@code "2CB"} and {@code "3GL+WM"} are the Cerebellum for instance 2 and the
 * Granular+WM for instance 3. ROI names with <em>no</em> leading number belong to
 * instance 1 (so existing single-section workflows need no changes at all). A fissure's
 * own index keeps working the same way after the instance prefix: {@code "2FL1"} is
 * instance 2's first fissure. There's no upper limit on how many instances can be
 * present at once — each one just needs the three required ROIs (Cerebellum, Granular+WM,
 * Purkinje) under that same leading number; White Matter and fissures are optional (see below).</p>
 *
 * <p><b>Naming caution:</b> because a leading number is now meaningful, avoid using bare
 * names like "2Cb" or "10Cb" for anything other than this instance-prefix feature —
 * previously such names were deliberately ignored (to avoid confusion with lobule output
 * labels), but they now mean "the Cerebellum ROI for instance 2/10". Output ROIs added by
 * {@link org.cerebellum.morphometry.visualization.RoiManagerExporter} are safe regardless,
 * since they always carry a suffix (e.g. "2Cb_Granular") that this parser doesn't match.</p>
 *
 * <h2>White Matter and fissures are optional</h2>
 * <p>Only three ROIs are ever required for an instance: Cerebellum, Granular+WM, and the
 * Purkinje line. White Matter and fissures are optional for <em>every</em> instance:</p>
 * <ul>
 *   <li><b>No White Matter traced?</b> Fine — some scan sections contain no white-matter core
 *       at all (e.g. a peripheral cut through cortex only). That instance's Grey Matter and
 *       Granular Layer numbers simply won't have White Matter excluded (there's nothing to
 *       subtract), and it isn't split into subsections. Any fissures traced anyway are ignored
 *       (with a log note), since a section with no white matter isn't partitioned into lobules.</li>
 *   <li><b>No fissures traced (but White Matter present)?</b> That instance just isn't split
 *       into subsections — its whole-cerebellum totals are still measured normally. Required for
 *       a primary instance that has White Matter; optional for any secondary instance, which may
 *       be traced only for its overall extent.</li>
 *   <li><b>Both traced?</b> Full behaviour — layer breakdown and lobule partitioning happen.</li>
 * </ul>
 */
public final class ROIValidator {

    /** How far a containment check may be off, as a fraction of area. */
    private static final double CONTAINMENT_TOLERANCE_FRACTION = 0.005; // 0.5 %

    private ROIValidator() {
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    /**
     * Validates the ROI Manager's contents and returns one {@link LayerSet} per detected
     * instance, keyed by instance number and sorted in ascending order. In the common case
     * (no instance-prefixed names present) this map has a single entry under key 1.
     */
    public static SortedMap<Integer, LayerSet> validate(RoiManager rm, ImagePlus imp) throws ValidationException {
        if (rm == null || rm.getCount() == 0) {
            List<String> problems = new ArrayList<>();
            problems.add("The ROI Manager is empty. Add the Cerebellum (or CB), Granular+WM (or GL+WM), "
                    + "and Purkinje line (or PL) before running this plugin. White Matter (or WM) and "
                    + "Fissure lines (FL1, FL2, …) are optional — trace them to exclude white matter and "
                    + "to split the section into lobules.");
            throw new ValidationException(problems);
        }
        return validate(rm.getRoisAsArray(), imp);
    }

    /**
     * Core validation logic, operating on a plain ROI array rather than a live {@link
     * RoiManager} — the public {@link #validate(RoiManager, ImagePlus)} entry point just
     * unwraps the manager (after checking it isn't null/empty) and delegates here. Exists
     * as its own method mainly so it can be exercised directly.
     */
    public static SortedMap<Integer, LayerSet> validate(Roi[] rois, ImagePlus imp) throws ValidationException {
        List<String> problems = new ArrayList<>();

        if (imp == null) {
            problems.add("No active image. Open the Nissl-stained section this plugin should measure.");
            throw new ValidationException(problems);
        }

        // -------------------------------------------------------------------
        // Group ROIs by instance number, classifying each by name (with its
        // leading-number instance prefix stripped first). Unrecognized names
        // are silently ignored, same as before.
        // -------------------------------------------------------------------
        SortedMap<Integer, InstanceBucket> buckets = new TreeMap<>();
        for (Roi roi : rois) {
            String name  = roi.getName() == null ? "" : roi.getName();
            String lower = name.toLowerCase();
            Classification c = classify(lower);
            if (c == null) {
                continue; // unrecognized ROI name — ignored, as before
            }
            InstanceBucket bucket = buckets.computeIfAbsent(c.instance, k -> new InstanceBucket());
            switch (c.category) {
                case FISSURE:
                    bucket.fissures.add(roi);
                    break;
                case PURKINJE:
                    if (bucket.purkinje != null) {
                        bucket.duplicates.add("Purkinje (\"" + bucket.purkinje.getName() + "\" and \"" + name + "\")");
                    }
                    bucket.purkinje = roi;
                    break;
                case GRANULAR_WM:
                    if (bucket.granularWM != null) {
                        bucket.duplicates.add("Granular+WM (\"" + bucket.granularWM.getName() + "\" and \"" + name + "\")");
                    }
                    bucket.granularWM = roi;
                    break;
                case CEREBELLUM:
                    if (bucket.cerebellum != null) {
                        bucket.duplicates.add("Cerebellum (\"" + bucket.cerebellum.getName() + "\" and \"" + name + "\")");
                    }
                    bucket.cerebellum = roi;
                    break;
                case WHITE_MATTER:
                    if (bucket.whiteMatter != null) {
                        bucket.duplicates.add("WhiteMatter (\"" + bucket.whiteMatter.getName() + "\" and \"" + name + "\")");
                    }
                    bucket.whiteMatter = roi;
                    break;
            }
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
            boolean isSecondary = instance != 1;

            for (String dup : b.duplicates) {
                problems.add(prefix + "More than one ROI matches " + dup + " — rename or remove one.");
            }

            if (b.cerebellum == null) {
                problems.add(prefix + "Missing the Cerebellum ROI. "
                        + "Add a closed polygon whose name contains \"cerebellum\" or the abbreviation \"CB\""
                        + (multiInstance ? " (with the \"" + instance + "\" prefix for this instance)." : "."));
            } else if (isLineType(b.cerebellum)) {
                problems.add(prefix + "The Cerebellum ROI (\"" + b.cerebellum.getName()
                        + "\") is a polyline, not a closed area. Retrace it as a closed polygon.");
            }

            if (b.granularWM == null) {
                problems.add(prefix + "Missing the Granular+WM ROI. "
                        + "Add a closed polygon whose name contains \"granular\", \"GL+WM\", \"GLWM\", or similar.");
            } else if (isLineType(b.granularWM)) {
                problems.add(prefix + "The Granular+WM ROI (\"" + b.granularWM.getName()
                        + "\") is a polyline, not a closed area. Retrace it as a closed polygon.");
            }

            // White Matter is optional for EVERY instance. Some scan sections simply contain no
            // white-matter core (e.g. a peripheral cut through cortex only), so requiring it would
            // block otherwise-valid sections. When it is absent, that instance's Grey Matter and
            // Granular Layer numbers just won't have White Matter excluded from them (there's
            // nothing to subtract), and — see the fissure handling below — no fissures are needed
            // or used, since a section with no white matter isn't split into lobules here.
            if (b.whiteMatter != null && isLineType(b.whiteMatter)) {
                problems.add(prefix + "The WhiteMatter ROI (\"" + b.whiteMatter.getName()
                        + "\") is a polyline, not a closed area. Retrace it as a closed polygon.");
            }

            if (b.purkinje == null) {
                problems.add(prefix + "Missing the Purkinje ROI. "
                        + "Add an open polyline whose name contains \"purkinje\" or the abbreviation \"PL\".");
            } else if (!isLineType(b.purkinje)) {
                problems.add(prefix + "The Purkinje ROI (\"" + b.purkinje.getName()
                        + "\") is a closed area, not a polyline. Retrace it as an open polyline.");
            } else if (!(b.purkinje instanceof PolygonRoi)) {
                problems.add(prefix + "The Purkinje ROI (\"" + b.purkinje.getName()
                        + "\") could not be read as a polyline.");
            }

            // Fissures are required only for a primary instance that HAS White Matter. They are
            // waived (and any that were traced are discarded) whenever White Matter is absent —
            // for any instance — because a section with no white-matter core isn't partitioned
            // into lobules; and they remain optional for secondary instances regardless, since
            // some traced pieces exist only for whole-cerebellum totals. When White Matter is
            // present, traced fissures are used normally.
            boolean fissuresRequired = !isSecondary && b.whiteMatter != null;
            boolean discardFissures = b.whiteMatter == null;
            List<PolygonRoi> fissurePolylines = new ArrayList<>();
            if (discardFissures) {
                if (!b.fissures.isEmpty()) {
                    IJ.log("[Cerebellar Morphometry] " + prefix + "Note: " + b.fissures.size() + " fissure "
                            + "ROI(s) found but ignored, since this instance has no White Matter ROI "
                            + "(a section with no white-matter core isn't split into lobules). Trace White "
                            + "Matter for this instance if you want it split into subsections.");
                } else {
                    IJ.log("[Cerebellar Morphometry] " + prefix + "Note: no White Matter ROI, so this "
                            + "instance won't be split into subsections, and its Grey Matter / Granular "
                            + "Layer numbers won't have White Matter excluded. Trace White Matter for this "
                            + "instance if you want either of those.");
                }
            } else if (b.fissures.isEmpty()) {
                if (fissuresRequired) {
                    problems.add(prefix + "No fissure ROIs found. Add at least one open polyline named "
                            + "\"fissure\" or \"FL1\", \"FL2\", … (see README for how many you need).");
                } else {
                    IJ.log("[Cerebellar Morphometry] " + prefix + "Note: no fissures traced for this "
                            + "instance, so it won't be split into subsections (whole-cerebellum totals "
                            + "are still measured normally). Trace fissures for this instance if you want "
                            + "lobule-level detail.");
                }
            } else {
                for (Roi f : b.fissures) {
                    if (!isLineType(f)) {
                        problems.add(prefix + "Fissure ROI \"" + f.getName()
                                + "\" is a closed area, not a polyline. Retrace it as an open polyline.");
                    } else if (!(f instanceof PolygonRoi)) {
                        problems.add(prefix + "Fissure ROI \"" + f.getName() + "\" could not be read as a polyline.");
                    } else {
                        fissurePolylines.add((PolygonRoi) f);
                    }
                }
            }

            // Containment checks (only run when the relevant ROIs are confirmed present and area-typed).
            // These are informational, not blocking: WhiteMatter or Granular+WM is allowed to reach or
            // extend past its "parent" shape's boundary. This is deliberately supported, not just
            // tolerated — at the cerebellar peduncle (where the cerebellum attaches to the brainstem),
            // there is no molecular/granular layering at all, so tracing White Matter out to meet the
            // Cerebellum boundary there is anatomically correct. It's also structurally useful: since
            // Cerebellum is a single closed outline and the fissures only span the foliated arc between
            // the first and last lobule, without a pinch point somewhere the ribbon FissurePartitioner
            // cuts stays a single closed loop and its two ends never separate. Deliberately closing the
            // White-Matter-to-Cerebellum gap at the peduncle gives that missing cut for free. See the
            // README's "Closing the loop" section. A large "outside" percentage is still logged, since
            // it can also indicate a genuine tracing or ROI-naming mistake.
            if (b.cerebellum != null && b.granularWM != null
                    && !isLineType(b.cerebellum) && !isLineType(b.granularWM)) {
                double granularWMArea = BooleanROIProcessor.area(b.granularWM, imp);
                double outside = BooleanROIProcessor.area(
                        BooleanROIProcessor.subtract(b.granularWM, b.cerebellum), imp);
                if (!withinTolerance(outside, granularWMArea)) {
                    IJ.log("[Cerebellar Morphometry] " + prefix + "Note: ~" + percentOutside(outside, granularWMArea)
                            + "% of the Granular+WM ROI's area falls outside the Cerebellum ROI. This is fine if "
                            + "intentional (e.g. tracing out to the pial surface at the peduncle to separate the "
                            + "first and last lobule) — otherwise, double check the two ROIs weren't traced from "
                            + "different sections or accidentally swapped.");
                }
            }
            if (b.granularWM != null && b.whiteMatter != null
                    && !isLineType(b.granularWM) && !isLineType(b.whiteMatter)) {
                double whiteMatterArea = BooleanROIProcessor.area(b.whiteMatter, imp);
                double outside = BooleanROIProcessor.area(
                        BooleanROIProcessor.subtract(b.whiteMatter, b.granularWM), imp);
                if (!withinTolerance(outside, whiteMatterArea)) {
                    IJ.log("[Cerebellar Morphometry] " + prefix + "Note: ~" + percentOutside(outside, whiteMatterArea)
                            + "% of the WhiteMatter ROI's area falls outside the Granular+WM ROI. This is fine if "
                            + "intentional (e.g. tracing out to the Granular+WM boundary at the peduncle to "
                            + "separate the first and last lobule) — otherwise, double check the two ROIs weren't "
                            + "traced from different sections or accidentally swapped.");
                }
            }

            boolean whiteMatterTypeOk = b.whiteMatter == null || !isLineType(b.whiteMatter);
            if (b.cerebellum != null && b.granularWM != null && b.purkinje != null
                    && (!fissuresRequired || !fissurePolylines.isEmpty())
                    && !isLineType(b.cerebellum) && !isLineType(b.granularWM) && whiteMatterTypeOk) {
                result.put(instance, new LayerSet(b.cerebellum, b.granularWM, b.whiteMatter,
                        (PolygonRoi) b.purkinje, fissurePolylines));
            }
        }

        if (!problems.isEmpty()) {
            if (multiInstance) {
                problems.add(0, "Detected " + buckets.size() + " instances from ROI name prefixes "
                        + buckets.keySet() + " (see \"Multiple instances\" in the README). If you only meant "
                        + "to trace one section, this usually means a ROI ended up with a name starting in a "
                        + "digit by accident — check for that instead of tracing a second instance.");
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

    /**
     * Strips an optional leading run of digits from {@code lower} (the instance number —
     * absent means instance 1) and classifies the remainder using the existing
     * priority-ordered name matchers. Returns {@code null} if nothing matches at all.
     */
    private static Classification classify(String lower) {
        int i = 0;
        while (i < lower.length() && Character.isDigit(lower.charAt(i))) {
            i++;
        }
        int instance = (i == 0) ? 1 : Integer.parseInt(lower.substring(0, i));
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

    /**
     * Cerebellum: "cerebellum" anywhere, OR whole-word "cb".
     */
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
        // Whole-word "gl" together with whole-word "wm" anywhere in the name.
        return isWholeWord(lower, "gl") && isWholeWord(lower, "wm");
    }

    /**
     * White Matter: "white" anywhere, OR whole-word "wm".
     * Because the dispatch chain tests Granular+WM first, a name like "GL+WM"
     * never reaches this predicate.
     */
    private static boolean matchesWhiteMatter(String lower) {
        return lower.contains("white") || isWholeWord(lower, "wm");
    }

    /**
     * Purkinje: "purkinje" anywhere, OR whole-word "pl"
     * (Purkinje Layer / Purkinje Line).
     */
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
            boolean beforeOk = idx == 0
                    || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            int afterIdx = idx + 2;
            boolean afterOk = afterIdx < lower.length()
                    && !Character.isLetter(lower.charAt(afterIdx));
            if (beforeOk && afterOk) return true;
            idx++;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Returns true when {@code token} appears in {@code lower} as a complete
     * word — bounded by non-alphanumeric characters or string start/end. This
     * predicate runs on the name AFTER any leading instance-number digits have
     * already been stripped by {@link #classify}, so a name like "2cb" arrives
     * here as plain "cb".
     * Examples: isWholeWord("cb_outline", "cb") → true,
     *           isWholeWord("flat", "fl")        → false (not a whole word).
     */
    private static boolean isWholeWord(String lower, String token) {
        int idx = 0;
        while ((idx = lower.indexOf(token, idx)) >= 0) {
            boolean beforeOk = idx == 0
                    || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            boolean afterOk  = (idx + token.length() >= lower.length())
                    || !Character.isLetterOrDigit(lower.charAt(idx + token.length()));
            if (beforeOk && afterOk) return true;
            idx++;
        }
        return false;
    }

    private static boolean isLineType(Roi roi) {
        int type = roi.getType();
        return type == Roi.LINE || type == Roi.POLYLINE || type == Roi.FREELINE;
    }

    private static boolean withinTolerance(double outsideArea, double referenceArea) {
        double tolerance = Math.max(referenceArea * CONTAINMENT_TOLERANCE_FRACTION, 1e-9);
        return outsideArea <= tolerance;
    }

    private static String percentOutside(double outsideArea, double referenceArea) {
        if (referenceArea <= 0) return "?";
        return String.format("%.1f", 100.0 * outsideArea / referenceArea);
    }
}
