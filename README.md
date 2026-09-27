# Cerebellar Layer Quantification Plugin

[![CI](https://github.com/dabcow/CerebellumInformationGrabber/actions/workflows/ci.yml/badge.svg)](https://github.com/dabcow/CerebellumInformationGrabber/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A FIJI/ImageJ plugin for quantitative morphometry of Nissl-stained cerebellar histology
sections. You trace the layers as ROIs; the plugin derives every layer area, the Purkinje
line length, and a per-lobule breakdown from that geometry. No pixel segmentation is done,
so results depend only on your tracing and the image calibration.

---

## Contents

- [What it measures](#what-it-measures)
- [Installation](#installation)
- [User workflow](#user-workflow)
  - [1. Open and calibrate the image](#1-open-and-calibrate-the-image)
  - [2. Trace and name the ROIs](#2-trace-and-name-the-rois)
  - [3. Run the plugin](#3-run-the-plugin)
- [How many sections you get](#how-many-sections-you-get)
- [Several separately traced pieces](#several-separately-traced-pieces)
- [Output](#output)
- [Batch processing with macros](#batch-processing-with-macros)
- [Troubleshooting](#troubleshooting)
- [How it works](#how-it-works)
- [Accuracy](#accuracy)
- [Building from source](#building-from-source)
- [Upgrading from 1.0.0](#upgrading-from-100)
- [License](#license)

---

## What it measures

| Measurement | Derived as |
|---|---|
| Cerebellum area | Area of the Cerebellum ROI |
| Grey Matter area | Cerebellum − White Matter |
| Granular Layer area | (Granular+WM) − White Matter |
| Molecular Layer area | Grey Matter − Granular Layer |
| Purkinje length | Length of the Purkinje polyline |

All five are reported for the **whole cerebellum**. The Granular, Molecular and Purkinje
values are also reported for every **fissure-defined section**. With 7 fissures and the ring
closed at the base ([Closing the loop](#closing-the-loop)), you get the standard **eight rodent
vermis lobules (2Cb – 10Cb)**.
Other counts are supported and are labelled generically ([details](#how-many-sections-you-get)).

The sections tile the grey matter exactly, so per-section areas and lengths add up to the
whole-cerebellum totals.

Results are shown as an ImageJ Results Table and can be saved as CSV and as an Excel
workbook. The workbook also records how the results were produced: plugin version, image,
calibration and any warnings.

---

## Installation

**Requirements:** FIJI (or ImageJ 1.54 or newer) running on **Java 17 or newer**. FIJI
downloads since 2025 bundle Java 21. Older FIJI installs that still run Java 8 can't load
the plugin. Check under *Help ▸ About ImageJ…*, and if needed download a current FIJI from
<https://fiji.sc>.

1. Download **[`cerebellar-layer-plugin-1.1.0.jar`](cerebellar-layer-plugin-1.1.0.jar)** from
   the top of this repository: open the file and click the download button (*Download raw
   file*). Tagged versions are also attached to the
   [Releases page](https://github.com/dabcow/CerebellumInformationGrabber/releases).
2. Copy it into FIJI's `plugins/` folder, and remove any older `cerebellar-layer-plugin-*.jar`.

   | OS | Default location |
   |---|---|
   | Windows | `C:\Program Files\Fiji.app\plugins\` (or wherever `Fiji.app` was unzipped) |
   | macOS | `/Applications/Fiji.app/plugins/` |
   | Linux | `~/Fiji.app/plugins/` |

3. Restart FIJI. The plugin appears under
   **Plugins ▸ Cerebellar Morphometry ▸ Quantify Layers…**

The jar is about 100 KB and has no dependencies of its own. It doesn't add any library to
FIJI's classpath.

---

## User workflow

### 1. Open and calibrate the image

Open the Nissl-stained section in FIJI and set the pixel size (*Analyze ▸ Set Scale…* or
*Image ▸ Properties…*). The plugin uses whatever calibration is present. Without one, areas
are reported in pixel² and lengths in pixels.

> Calibrate in **µm** rather than mm. The CSV is written with 4 decimal places, which in
> mm² can leave only two or three significant figures for a small lobule. The Excel workbook
> always stores full precision.

### 2. Trace and name the ROIs

Open the ROI Manager (*Analyze ▸ Tools ▸ ROI Manager*) and add these ROIs. Names are matched
**case-insensitively**, and both full names and common abbreviations work. Order doesn't matter.

| ROI | Draw with | Accepted names (examples) | Required? |
|---|---|---|---|
| Whole cerebellum outline | Polygon / Freehand | `CB`, `Cerebellum`, `CB_left` | Yes |
| Granular layer + White Matter | Polygon / Freehand | `GL+WM`, `GLWM`, `GL_WM`, `GL-WM`, `GL WM`, `Granular+WM`, `granular` | Yes |
| Purkinje cell line | Segmented Line (or Freehand / straight Line) | `PL`, `Purkinje` | Yes |
| White Matter only | Polygon / Freehand | `WM`, `WhiteMatter`, `white matter` | Optional¹ |
| Fissures 1, 2, 3, … | Segmented Line (or Freehand / straight Line) | `FL1`, `FL_1`, `FL-1`, `Fissure1`, `fissure 1`, … | Optional² |

¹ Without White Matter, the section isn't split into lobules, and its Grey Matter and
Granular Layer values include the white-matter core (there's nothing to subtract).
Some sections, such as a peripheral cut through cortex only, genuinely have no white-matter core.
² Required whenever White Matter is traced (for the first piece). Trace as many as the
section shows. There's no upper limit.

Abbreviations are matched as whole words: `CB` matches `CB_left` but not `CBx1`, and `FL`
doesn't match `flat`. `GL+WM` is always Granular+WM, never White Matter.

**Example ROI Manager list** (full names work identically):

```
CB
GL+WM
WM
PL
FL1
FL2
FL3
FL4
FL5
FL6
FL7
```

#### Tracing tips

**Cerebellum, Granular+WM, White Matter:** closed outlines, normally nested
(White Matter ⊂ Granular+WM ⊂ Cerebellum). Follow the Nissl staining rather than atlas
borders. It's fine for an inner outline to reach or cross its parent at the peduncle. The
plugin only logs a note, since that's usually deliberate. Composite selections are fine too,
e.g. an outline made with the ROI Manager's *More ▸ XOR* and then trimmed with Alt + Polygon to
exclude adjacent non-cerebellar tissue.

**Purkinje line:** an open line along the middle of the Purkinje cell layer, from the first
lobule to the last. **Trace it clockwise.** With the section oriented rostral-left and dorsal-up,
that runs from lobule 2 over the top to lobule 10. Sections are numbered from the line's first
point, so a counterclockwise trace numbers them in reverse. The plugin warns when it detects one.
Avoid abrupt zig-zags.

**Fissures:** one line per fissure, **starting at the pial surface** and running down the
centre of the fold, **through the Granular+WM boundary and ending inside White Matter** near
its apex, not beyond it. Use as few points as follow the fold, typically a start point, one or
two turning points and an end point. Adjacent fissures may nearly meet near the white-matter
apex, but they **must never cross**. If a line stops short, the plugin extends its ends along
their own direction until the cut runs from just outside the cerebellum into White Matter. You
can trace either end first; the pial end is detected automatically.

#### Closing the loop

The Cerebellum outline is one closed shape, but the fissures and Purkinje line only span the
foliated part. They stop short of the base, where the cerebellum joins the brainstem. When
the outlines are traced straight across the base, a band of tissue there still connects the
first and last lobules (2Cb and 10Cb), and they are reported as **one** section. The plugin
warns when this happens.

Separate them in either of two ways:

- **Add a fissure line across the base** (recommended). Trace one more fissure line, named
  like the others (e.g. `FL8`), across the base of the cerebellum between the first and last
  lobules, from outside the Cerebellum outline into White Matter. It acts as the eighth cut.
- **Or let the outlines meet at the base.** Extend **Granular+WM** out to touch the Cerebellum
  outline at the peduncle, and **White Matter** out to touch or cross both there. This pinches
  the grey-matter ring open into a strip.

### 3. Run the plugin

Save the ROI set first (*ROI Manager ▸ More ▸ Save…*), then choose **Plugins ▸ Cerebellar
Morphometry ▸ Quantify Layers…**. The dialog remembers the checkbox choices between runs.

| Option | Effect | Macro keyword |
|---|---|---|
| Layer overlay (colour-coded) | Draws Grey, Granular and Molecular layers, the Purkinje line and the fissure cuts over the image | `layer_overlay` |
| Section fills and labels | Gives each section its own translucent colour and a name label on the image | `section_fills` |
| Add ROIs to ROI Manager | Adds one ROI per measured region ([details](#measurement-rois)) | `add_rois` |
| Results table | Shows the results in an ImageJ Results Table | `results_table` |
| Save CSV | Saves `Output.csv` | `save_csv` |
| Save Excel workbook (.xlsx) | Saves `Output.xlsx`, with a "Run Info" sheet | `save_excel` |
| File name | Name of the result files, `Output` by default. Change it to save several sections into one folder | `file_name` |
| Output folder | Where files are saved. Defaults to the folder the image was opened from, so each section's results sit next to its image. Leave empty to be asked | `output_folder` |

If warnings come up during the run, a summary dialog lists them at the end. The details are
in the Log window (*Window ▸ Log*) and in the workbook's *Run Info* sheet. Before overwriting
existing result files, the plugin asks first.

---

## How many sections you get

The layers wrap all the way around the white-matter core, so the grey matter is a **ring**.
The plugin detects its shape and tells you in the Log which case applies:

- **Ring:** *N* fissure lines give *N* sections. Cutting a ring once only opens it. With only
  the real fissures traced, the first and last lobules stay joined as one section, so add a
  line across the base (see [Closing the loop](#closing-the-loop)).
- **Strip (outlines pinched open at the peduncle):** *N* fissures give ***N + 1*** sections.

**7 fissures + a line across the base (or the peduncle pinch) → 8 sections** is the standard
scheme for a midline
sagittal section of rodent vermis. These get the anatomical names 2Cb, 3Cb, 4/5Cb, 6Cb, 7Cb,
8Cb, 9Cb and 10Cb, ordered along the Purkinje line from its first traced point.

Any other result gets generic names ("Section 1", "Section 2", …) because a specific
anatomical name would be a guess. That applies to off-midline, coronal or damaged sections,
other species, and also to eight sections in which the first and last lobules are still
joined.

---

## Several separately traced pieces

If the tissue can't be traced as one outline (a piece broke off during sectioning, or the cut
caught disconnected islands), trace each piece as its own set of ROIs. Prefix every ROI name
of the second piece with `2`, the third with `3`, and so on. Unprefixed names belong to piece 1.

```
CB, GL+WM, WM, PL, FL1 … FL7        ← piece 1
2CB, 2GL+WM, 2WM, 2PL, 2FL1 … 2FL4  ← piece 2 (its own fissure count)
3CB, 3GL+WM, 3PL                    ← piece 3 (no WM or fissures: measured whole)
```

The pieces are parts of **one** cerebellum, so they're pooled into a single set of results:

- Whole-cerebellum totals are **summed** across pieces.
- Sections are **pooled** in piece order, then in Purkinje-line order within each piece. A
  piece without fissures contributes itself as one section. Number the pieces in anatomical
  order.
- Labels are assigned across the pooled list: exactly eight sections get the standard names,
  any other count gets generic ones.

Every piece needs its own Cerebellum, Granular+WM and Purkinje ROIs. White Matter and fissures
are optional for pieces 2, 3, …

> **Naming caution:** a leading number means "piece N", so don't name anything else `2Cb` or
> `10Cb`. Leading numbers longer than 4 digits, such as a date, are not treated as piece
> numbers. ImageJ's default names like `0512-1024` don't match any layer and are ignored.

---

## Output

### Results table (also CSV and Excel)

```
Measurement  | Cerebellum | Grey Matter | Granular Layer | Molecular Layer | Purkinje
─────────────┼────────────┼─────────────┼────────────────┼─────────────────┼─────────────
Area         | total      | total       | total          | total           | "area" ¹
Length       |            |             |                |                 | total length
2Cb          |            |             | area           | area            | length
3Cb          |            |             | area           | area            | length
…            |            |             | …              | …               | …
10Cb         |            |             | area           | area            | length
```

Column headers carry the calibrated unit, e.g. `Granular Layer (µm²)` and `Purkinje (µm)`.
Numbers always use a `.` decimal separator, whatever the computer's language settings. The
CSV has 4 decimal places. The Excel workbook stores full precision and displays 4 places, with
bold summary rows and a frozen header row and label column.

¹ A line has no real area. This cell reproduces what ImageJ's *Analyze ▸ Measure* reports for
the Purkinje ROI (pixels visited × pixel area), for parity only.

The workbook's **Run Info** sheet records the plugin version, ImageJ and Java versions, date,
image name and file, image size, pixel size, how each piece was partitioned, and every note
and warning from the run.

### Overlay

| Item | Colour |
|---|---|
| Grey Matter | Blue |
| Granular Layer | Green |
| Molecular Layer | Yellow |
| Purkinje line | Red |
| Fissure cuts (as extended by the plugin) | White |
| Section fills (optional) | 12 distinct hues, cycling if there are more sections, plus a name label |

Re-running replaces only the plugin's own overlay items. Anything else on the overlay, such as
a scale bar, is kept. To remove the overlay entirely, use *Image ▸ Overlay ▸ Remove Overlay*.

### Measurement ROIs

With *Add ROIs to ROI Manager*, one ROI is added per measured region:

| Name | What it is |
|---|---|
| `Grey Matter`, `Granular Layer`, `Molecular Layer` | The whole-cerebellum layers |
| `<section>_Granular`, `<section>_Molecular` | That section's clipped layer, e.g. `2Cb_Granular` |
| `<section>_Purkinje` | That section's stretch of the Purkinje line |
| `<section>_Purkinje_1`, `_2` | The same, when a section owns two separate stretches (the wrap-around section of a ring) |

Measuring these ROIs in ImageJ reproduces the table. With several pieces, names get an `[N] `
prefix. The ROIs are tagged as plugin output, so the plugin can be re-run on the same ROI
Manager without them being mistaken for input, even after saving and reopening the ROI set.

---

## Batch processing with macros

Every dialog option has its own macro keyword (see [the options table](#3-run-the-plugin)), so
a run can be recorded with *Plugins ▸ Macros ▸ Record…* and replayed:

```javascript
// For each open image whose ROIs are in the ROI Manager.
// Saves Output.csv and Output.xlsx next to the image, as in the lab SOP:
run("Quantify Layers...", "layer_overlay section_fills results_table save_csv save_excel");

// Or collect every section in one folder, each under its own name:
run("Quantify Layers...", "results_table save_csv save_excel file_name=[Mouse_001_Section_02] output_folder=[C:/data/results]");
```

In a macro, existing files are overwritten without asking, so give each section its own
`file_name` when they share an `output_folder`. No summary dialog is shown.
Warnings still go to the Log window and the workbook.

---

## Troubleshooting

### Validation errors (the run stops)

| Message | Fix |
|---|---|
| *The ROI Manager is empty* / *No ROIs with recognized names were found* | Add and name the ROIs as in [step 2](#2-trace-and-name-the-rois) |
| *Missing the Cerebellum / Granular+WM / Purkinje ROI* | Add it, with a name from the table above |
| *No fissure ROIs found* | Trace at least one fissure (`FL1`, …), or remove White Matter if the section shouldn't be split |
| *… is a closed area, not a line* / *… is not a closed area* | Retrace the Purkinje line and fissures as lines, and the outlines as closed shapes |
| *More than one ROI matches …* | Two names match the same layer. Rename or remove one, or add the right [piece prefix](#several-separately-traced-pieces). If one of them is `Granular Layer` from a run of version 1.0.0, delete it |
| *[Instance N] Missing the …* | A name starting with the digit N created piece N. Add its other ROIs, or rename the stray ROI |

### Warnings and notes (the run completes)

| Message | Meaning |
|---|---|
| *The first and last lobules are joined into one section…* | A section is missing because the ring isn't closed at the base. Add a fissure line across the base between the first and last lobules (see [Closing the loop](#closing-the-loop)). With eight sections, generic names are used because 2Cb … 10Cb would be wrong |
| *The Purkinje line was traced counterclockwise…* | Sections are numbered in reverse. Retrace the Purkinje line clockwise |
| *The pieces pool into 8 sections, but … joined…* | Like the first row, for one of several [separately traced pieces](#several-separately-traced-pieces) |
| *Could not split the grey matter cleanly into N sections…* | A fissure doesn't cross the whole grey matter. Check the white cut lines in the overlay for one that stops short or cuts across a fold instead of along it |
| *The sections only cover X% of the grey matter area…* | Some grey matter wasn't assigned to any section. Check the section fills in the overlay |
| *X% of the Purkinje line lies outside the grey matter…* | Part of the Purkinje trace runs outside Cerebellum − White Matter. It counts toward the total length but not toward any section |
| *~X% of the Granular+WM (or White Matter) ROI's area falls outside…* | Informational. Expected at the peduncle; worth a look if it's large |
| *Ignored N ROI(s) whose names don't match any expected layer…* | Lists ROIs the plugin didn't use. Check for typos |

### Other

| Symptom | Cause |
|---|---|
| Plugin missing from the menu | FIJI runs Java 8 (see [Installation](#installation)), or the jar isn't in `plugins/` |
| Areas in pixel² instead of µm² | The image isn't calibrated. Use *Analyze ▸ Set Scale…* |
| Sections labelled "Section 1, 2, …" | Expected whenever the result isn't exactly eight separated lobules. See [How many sections](#how-many-sections-you-get) |
| Section labels reversed | The Purkinje line was traced counterclockwise (the plugin warns). Retrace it clockwise |
| Extra, tiny, or duplicated sections | A fissure line cuts off an extra piece of grey matter. Simplify it (fewer points, less curvature), and check that no two fissures cross. Pieces under 1.5% of the grey matter are merged into a neighbour automatically |
| A section has no Purkinje segment | The Purkinje line runs outside the Cerebellum outline or into White Matter there (see the *…lies outside the grey matter* note). Move it back onto the Purkinje cell layer |

---

## How it works

```
org.cerebellum.morphometry
├── CerebellarMorphometryPlugin   FIJI entry point: options dialog, runs the pipeline, exports
├── Diagnostics                   Collects notes/warnings (Log window, summary dialog, workbook)
├── PluginOutput                  Tags ROIs/overlay items the plugin creates
├── BuildInfo                     Plugin version, stamped at build time
├── model/                        LayerSet, ConstructedLayers, PartitionSet, MorphometryResults,
│                                 InstanceResult, RunInfo, ValidationException
├── geometry/
│   ├── ROIValidator              Name / type / instance checks → one LayerSet per piece
│   ├── LayerConstructor          Grey, Granular, Molecular by Boolean subtraction
│   ├── FissurePartitioner        Fissures → lobule regions that tile the grey matter
│   ├── RasterSplitUtils          Pixel-mask splitting, labelling and partition completion
│   ├── PartitionClipper          Per-lobule Granular / Molecular shapes
│   ├── PurkinjeLengthCalculator  Calibrated length, clipped per lobule
│   ├── BooleanROIProcessor       Defensive-copy ShapeRoi AND / NOT + calibrated area
│   └── GeometryUtils             Vector and polyline helpers
├── measurement/MeasurementEngine Orchestrates geometry and measurement; pools pieces
├── export/                       SpreadsheetExporter (table, CSV, XLSX), XlsxWriter
└── visualization/                OverlayRenderer, RoiManagerExporter
```

### Partitioning

1. **Orient** each fissure from pial surface to white matter, by which endpoint is nested
   more deeply (inside White Matter › Granular+WM › Cerebellum › outside).
2. **Extend** each fissure along its own end directions until it runs from just outside the
   Cerebellum into White Matter. The traced middle is kept exactly. Extension stops as soon
   as its target is reached and is distance-capped, so a cut is never shot blindly across the
   section.
3. **Split the grey matter** (Cerebellum − White Matter) once. It's rasterized to a pixel
   mask, thin strips around the cuts are subtracted, and the pieces are labelled as connected
   components. A pixel mask is used because Java's vector `Area` geometry fragments
   real-world tracings into dozens of spurious pieces. The number of pieces expected comes
   from the mask's topology (ring or strip, see above). Strip widths are tried thinnest-first
   until one gives that number.
4. **Complete the partition.** The strip pixels are handed back to the nearest piece, which
   puts every boundary on the fissure line itself. Slivers (under 1.5% of the grey matter)
   are merged whole into their neighbour. The sections then tile the grey matter with no gaps.
5. **Order and name** the pieces by where each piece's own stretch of the Purkinje line lies
   (median position along the line). Standard names are applied only to eight properly
   separated sections.
6. **Measure.** Each section's Granular and Molecular areas are its footprint intersected with
   the whole-cerebellum layers. Its Purkinje length comes from clipping the Purkinje line
   against the footprint at sub-pixel resolution.

---

## Accuracy

The test suite includes a synthetic section with closed-form answers: concentric layers with
radial fissures, where every interior lobule is an exact annular sector. On it, every interior
lobule's granular area, molecular area and Purkinje length is within **0.3%** of the exact
value. The sections sum to the whole-layer totals within **0.1%**. This holds for a finely
traced Purkinje line and for a coarse Segmented Line trace (≈ 73 px segments). Version 1.0.0
measured the same lobules 4–7% low, and up to 20% low with the coarse trace (see the
[changelog](CHANGELOG.md)).

---

## Building from source

Needs a JDK 17 or newer (e.g. from <https://adoptium.net>). Maven doesn't need to be
installed: the included wrapper downloads the right version on first use.

```bash
./mvnw verify
```

On Windows use `mvnw.cmd verify`. This compiles, runs the tests, and writes
`target/cerebellar-layer-plugin-<version>.jar`.

- Tests run headless and use a synthetic section (`src/test/java/.../testing/SyntheticSection.java`),
  so no images or FIJI are needed.
- CI builds and tests on Linux and Windows with Java 17 and 21 for every push and pull request.
- **The jar in the repository root is the download users install**, so rebuild it whenever the
  source changes. To release: set `<version>` in `pom.xml`, update `CHANGELOG.md`, run
  `./mvnw verify`, and replace the jar in the repository root with the new
  `target/cerebellar-layer-plugin-<version>.jar` (keep only one). CI fails if that jar is
  missing or built from another version, and warns if it differs from a fresh build of the
  source. Pushing a tag `v<version>` also publishes the jar on the Releases page.

---

## Upgrading from 1.0.0

Version 1.1.0 corrects how per-lobule values are measured (see [CHANGELOG.md](CHANGELOG.md)).
Per-lobule areas and Purkinje lengths are typically a few percent **higher** than 1.0.0
reported, and now add up to the totals. Whole-cerebellum totals are unchanged. **Don't mix
per-lobule numbers from 1.0.0 and 1.1.0 in one analysis.** Re-run the earlier sections
instead; the ROI sets you saved can be reused as they are.

Result files are now named `Output.csv` and `Output.xlsx` (as in the lab SOP) instead of after
the image (e.g. `Montage.csv`). Use the *File name* option for other names.

Remove the old 23 MB `cerebellar-layer-plugin-1.0.0.jar` from `plugins/` when installing
1.1.0.

---

## License

[MIT](LICENSE). ImageJ is used under its own public-domain terms.
