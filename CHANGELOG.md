# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.1.0] — 2026-09-26

### Measurement changes: per-lobule values differ from 1.0.0

Whole-cerebellum totals are unchanged. Per-lobule values now add up to them. Don't mix
per-lobule results from 1.0.0 and 1.1.0; re-run earlier sections instead.

- **Per-lobule areas no longer lose the tissue along each fissure.** Lobules are separated by
  subtracting thin strips around the fissures, and those strips were never given back, so
  every lobule lost a band of tissue along each boundary. On the synthetic test section,
  interior lobules came out 6% low (granular) and 4% low (molecular), and all sections
  together covered only 95–97% of the layer totals. Strip pixels are now assigned to the
  nearest lobule, putting each boundary on the fissure line. Lobules are within 0.3% of the
  exact values, and sections sum to the totals within 0.1%.
- **Per-lobule Purkinje length is clipped accurately.** Each segment of the Purkinje line used
  to be credited wholly to whichever lobule contained its midpoint. Segments whose midpoint
  fell inside a fissure strip were dropped entirely. Lengths were 4–7% low with a finely
  traced line and 20% low with a typical Segmented Line trace (≈ 73 px segments). The line is
  now clipped at sub-pixel resolution: within 0.3% in both cases.
- **Slivers are merged instead of discarded.** The sliver-merging step added in the last 1.0.0
  build could never find a neighbour, because distinct connected components are never
  adjacent. So every sliver was silently dropped with its area. Slivers are now merged whole
  into the lobule they border.
- **Joined first and last lobules are reported, and never given standard names.** When the ring
  of grey matter isn't closed at the base, one section holds both the first and last lobule.
  1.0.0 only logged a note that could be misread. When that made eight sections, it still named
  them 2Cb … 10Cb. This is now a warning that a section is missing, naming the fix (a fissure line
  across the base, as in the lab SOP). Generic names are used in that case.
- **Lobules are ordered by their own stretch of the Purkinje line** (median position) rather
  than by projecting each lobule's centroid onto it. A curved or branched lobule's centroid
  can sit nearer a neighbour's stretch of the line.

### Fixed

- Running the plugin a second time on the same ROI Manager failed validation after *Add
  measurement ROIs* had been used. Output names such as `Granular Layer`, `2Cb_Granular` and
  `10Cb_Molecular` were read back as input: a duplicate Granular+WM and spurious pieces 2, 4
  and 10. Output ROIs are now tagged (the tag survives saving the ROI set), and untagged 1.0.0
  output names are recognised too.
- On computers using a comma decimal separator (German, French, …), the CSV contained values
  like `453660,0000` and every number in the Excel file was stored as text. Numbers are now
  written independently of the locale, and the workbook stores real numbers at full precision.
- A ROI name beginning with more than about ten digits, such as a timestamp, crashed the
  plugin with an uncaught `NumberFormatException`.
- Fissures or a Purkinje line drawn with the straight *Line* tool were rejected with *"could
  not be read as a polyline"*. They're now accepted.
- The CSV export used a `PrintWriter`, which swallows write errors, so a failed write (e.g. a
  full disk) was reported as "CSV saved". Write errors are now reported. Both files are
  written to a temporary file and moved into place, so a failed export never leaves a
  truncated file or destroys the previous one.
- The whole geometry pipeline ran twice per run (once for the overlay and once for the
  numbers), so every Log message appeared twice.
- The overlay drew the Purkinje line as the outline of a 1-pixel-wide area, because wrapping a
  line in a `ShapeRoi` converts it to an area. It's now drawn as a line.
- Layer fills in the overlay were crossed by spurious horizontal lines, the edges of the slabs
  Java2D splits a subtracted shape into. The same applied to the exported whole-layer and
  per-section ROIs. They're now re-traced into clean outlines covering exactly the same pixels.
- Re-running replaced the image's entire overlay, deleting e.g. a scale bar. Only the plugin's
  own items are replaced now.
- With *Add measurement ROIs*, the `_Purkinje` ROI of a section owning two separate stretches
  of the line contained only the longer one. Both are now added, and together they measure
  the reported length. A perfectly horizontal or vertical stretch was skipped entirely.
- In a recorded macro, the three "Show …" options shared one keyword (`show`) and the two
  "Export …" options another (`export`), so they couldn't be set independently. Every option
  now has its own keyword.
- If no image was open, `IJ.getImage()` aborted with its own generic error before the plugin's
  explanatory message could appear.
- Running with no ROI Manager open popped up an empty ROI Manager window.
- A plain rectangle ROI rasterized as empty in the partitioning step.

### Added

- *Run Info* sheet in the Excel workbook: plugin version, ImageJ and Java versions, date, image
  and file, image size, pixel size, how each piece was partitioned, and every note and warning.
- End-of-run summary dialog when there are warnings (not shown when run from a macro).
- Checks that the sections really cover the grey matter and the Purkinje line, with a warning
  or note if not.
- Name labels for each section on the overlay, and a 12-colour palette (the README already
  promised 12; the code had 8).
- *Output folder* field in the dialog, so a run can be fully scripted. It defaults to the folder
  the image was opened from, so with one folder per section (as in the lab SOP) each section's
  results are saved next to its `Montage.tif`.
- The dialog remembers its checkbox settings between runs.
- Warning when the Purkinje line is traced counterclockwise. Sections are numbered from the
  line's first point, so a counterclockwise trace numbers them in reverse (the SOP's "section
  labels reversed" problem).
- Asks before overwriting existing result files (interactive runs only).
- Log note listing ROIs whose names weren't recognised.
- Test suite (105 tests): name matching, a synthetic section with closed-form answers, and the
  CSV/XLSX output, which is read back with Apache POI. `SopScenariosTest` encodes the lab SOP's
  workflow: every name in its naming table, its detached-pieces example (`CB` … `4CB`, `fl1`),
  closing the ring with a fissure line across the base, clockwise and counterclockwise Purkinje
  lines, XOR-trimmed outlines, one Purkinje segment per section, and micrometer calibration. On
  these scenarios, 1.1.0 gives the same section counts and names as 1.0.0.

### Changed

- **The plugin jar is about 100 KB instead of 23 MB.** 1.0.0 bundled a full copy of ImageJ,
  SciJava (unused) and Apache POI, with commons-io, commons-compress, commons-codec, log4j and
  xmlbeans. FIJI ships most of these itself, usually at other versions, which is a known cause
  of classpath conflicts. ImageJ is now `provided`, and `.xlsx` files are written by a small
  built-in writer.
- About 4× faster, with 2–3× lower peak memory, on large sections: 2.0 s vs 7.9–9.4 s, and
  ~430 MB vs ~1.2 GB (4 GB heap), on a 6000 × 5000 px test section. The pixel-mask code no longer boxes
  one `Integer` per pixel.
- The source is committed as a normal Maven project. Before, it existed only inside a
  `.tar.gz` in the repository, next to a committed build of the jar, so no change could be
  reviewed or diffed. The ~100 KB plugin jar is still committed at the repository root for
  direct download; CI checks it matches the project version and warns if it differs from a
  fresh build. Tagged versions are also published on GitHub Releases.
- Maven Wrapper included. Maven no longer needs to be installed to build.
- README rewritten to match the actual behaviour. Several sections contradicted the code:
  whether piece 1 needs White Matter, when standard names apply, the fill palette, and error
  messages that no longer existed.

### Project

- GitHub Actions CI (Linux and Windows, Java 17 and 21), tag-triggered release workflow, and
  Dependabot.
- `LICENSE` file added. The previous README declared MIT but the file was missing.
- `.gitignore`, `.editorconfig`, reproducible-build timestamp, and build-environment enforcement.

## [1.0.0] — 2026-07-16

Initial release, distributed as a pre-built jar and a source tarball committed to the
repository. The version number stayed at 1.0.0 through several algorithm changes between
2026-07-01 and 2026-07-16, so results labelled 1.0.0 don't all come from the same code.

[1.1.0]: https://github.com/dabcow/CerebellumInformationGrabber/compare/a8ad4e3...v1.1.0
[1.0.0]: https://github.com/dabcow/CerebellumInformationGrabber/tree/a8ad4e3
