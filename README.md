# ScriptLogLite

A Swing text area records document edits, caret movements, key
presses/releases, and scrolling. Requires a JDK (Java 11 or newer). Nimbus is included with Java; the default
launcher requires no additional libraries or downloads:

```sh
./run.sh
```

The main `JFrame` contains a `JTabbedPane`. Each document and replay viewer has
its own closable tab. New and Open Log create separate documents with independent
text, selection, scroll position, and logging history. Tabs scroll when there
are too many to fit. A compact toolbar provides New, Open, Save, and Replay.
The application starts with Nimbus. **View → Theme** includes Nimbus and optional
FlatLaf Light / Dark themes. FlatLaf remains supported but is never activated
at startup. Switching preserves text, selection, logging history, and replay
position; the choice applies for the current run.

To download/cache FlatLaf 3.7 and make its themes usable, run:

```sh
./run.sh --with-flatlaf
```

This uses `curl` and Maven Central on the first run. Later runs automatically
include the cached JAR on the classpath, while still starting in Nimbus. Maven
builds also include FlatLaf without activating it. Selecting a FlatLaf theme
without its JAR shows instructions for enabling support.

The menus provide:

- **File**: New, Open Log, Save Log, Save Log As, Close, Exit.
- **Edit**: Cut, Copy, Paste, Select All, and example insert/replace/remove commands.
- **View**: Replay Current Log, Open Log for Replay, and Nimbus/Light/Dark themes.
- **Tabs**: Next Tab, Previous Tab, Close All, and a list of open tabs.
- **Settings**: JSON/Raw/Inputlog IDFX save formats.
- **Analysis**: General Analysis, Summary Analysis (PT0), S-Notation, Word Pauses and Linear Analysis, with Inputlog HTML comparison and HTML export.
- **Help**: About.

Common commands have Ctrl keyboard shortcuts, including Ctrl+N, Ctrl+O, Ctrl+S,
Ctrl+Shift+S, and Ctrl+W. Ctrl+PageDown and Ctrl+PageUp switch tabs. Document commands operate on the selected document and
are disabled when a replay viewer is selected. An asterisk marks unsaved log
events. Closing a document or exiting offers Save / Discard / Cancel for changed
histories. Replay timers stop when their tabs close.

Type, paste, delete, or use the Edit menu's insert/replace/remove commands. The filter
allows edits through. Swing often calls `replace` for typing. Document operations
that Swing ignores before reaching the filter do not produce filter events.
Events are recorded in JSON logs without printing them to stdout.

Recording editors wrap at the right edge (at word boundaries where possible),
with the vertical scrollbar always visible and the horizontal scrollbar hidden.
Open and Save dialogs remember separate directories across runs. Successful
opens and saves update their respective directory, stored in
`~/.config/scriptloglite/directories.properties`. Missing directories fall back
to `~/ScriptLogLiteWD`.

## Raw logs and save formats

Open Log and Open Log for Replay recognize both JSON and compact raw logs by
content, including `exp_subj_raw_1.txt`. Raw logs contain `key: value` header lines,
a line containing only `#`, and one event per line:

```text
startTime: 1000000000
#
1100000000 0.100 <replace> 0 0 Hello\sworld
```

The reader reconstructs text and reversible edits just as it does for JSON.
Spaces/newlines use the sample's `\s` / `\n` escapes. New raw exports also support
`\r`, `\t`, `\uXXXX`, `\e` (empty string), and `\N` (null), so pasted text and
literal backslashes round-trip safely. String header values are quoted using
JSON escaping. Optional named fields after keyboard events preserve modifiers,
characters, and key locations when present.

**Settings → Save formats** offers independent **JSON** and **Raw** checkboxes.
JSON alone is the default. Select either format or both; at least one must remain
selected. The choice is remembered across runs and applies to named saves and
automatic logs, including already open documents. Both uses matching basenames:
`exp_subj_sll_1.json` and `exp_subj_sll_1.txt`. Each output is written in the
background using the same captured history. Existing outputs are retained when
a format is later disabled. Save As normalizes extensions and asks before
replacing any selected output. File replacement is atomic per output when
supported, rather than a transaction spanning both files.

## JSON log format

Saved logs and automatic recording logs use the same structure as
`exp_subj_json_1.json`: `[[metadata], [events]]`. For example:

```json
[
  [{"startTime": 1000000000, "endTime": 1100000000, "initialText": ""}],
  [
    {
      "when": 1100000000,
      "relativeTime": "0.100",
      "event": "<replace>",
      "eventID": 103,
      "offset": 0,
      "length": 0,
      "str": "Hello"
    }
  ]
]
```

The recorder includes the sample's metadata fields, such as font, text-area
geometry, OS, and blank participant/task identifiers. `when`, `startTime`, and
`endTime` are integer nanoseconds on a session clock; `relativeTime` is a string
in seconds with three decimal places. Playback uses the precise `when` values.
As in the supplied sample, `tokensInFinalText` is the final text length (Java
UTF-16 code units). The sample has 206 characters and 36 whitespace-delimited
words, and its `tokensInFinalText` value is 206.

| Event | eventID | Fields |
| --- | --- | --- |
| `<insertString>` | 101 | offset, length (0), str |
| `<remove>` | 102 | offset, length |
| `<replace>` | 103 | offset, length, str |
| `<caretUpdate>` | 104 | dot, mark |
| `<scrollChange>` | 107 | viewX, viewY (pixels) |
| `<keyPressed>` | 207 | keyCode |
| `<keyReleased>` | 208 | keyCode |

The sample has no insertString event; 101 is the recorder's assigned ID for it.
Keyboard entries retain additional character/modifier/location details when
available. Two additional metadata fields, `initialText` and
`recordingStartTime`, support initially nonempty documents and preserve internal
wall-clock timestamps. The sample itself needs neither: it reconstructs from an
empty document using its nanosecond clock. Removed text is reconstructed from
the edit sequence, so saved JSON does not need `oldText` fields.

## Save, open, and continue

**File → Save Log** saves the complete selected document history;
**Save Log As…** chooses another filename. **Open Log…** restores text,
caret, selection, and scroll position, then lets you continue editing. Saving
again retains earlier events and adds new ones. No separate document file is
needed. Opening adds a new tab and keeps existing documents open.
Save As asks before replacing an existing file.

```sh
./run.sh --open saved-document.json
./run.sh --open exp_subj_json_1.json
```

At startup the application creates `~/ScriptLogLiteWD` if needed. This is the
default directory for Save/Open dialogs when there is no valid remembered
directory. Explicitly chosen Open and Save directories are still remembered.

Automatic recordings always live under this working directory. The current
variables are **Experiment = `exp`**, **Condition = `_`**, and **Subject = `subj`**.
The directory and filename prefix concatenates those three values, giving
`exp_subj`. For example:

```text
~/ScriptLogLiteWD/
  exp_subj/
    2026-10-08_1/
      exp_subj_sll_1.json
    2026-10-08_2/
      exp_subj_sll_1.json
    2026-10-09_1/
      exp_subj_sll_1.json
```

Each new document or opened recording gets a fresh automatic recording folder.
The numeric index increases within each date and Experiment/Condition/Subject
group, starting at 1 for a new date and continuing across application restarts. Directories are reserved
atomically, so simultaneous recordings cannot reuse a folder. The filename index
is independent: it starts at `sll_1` in each folder, using `sll_2`, `sll_3`, etc.
only if those preceding filenames already exist in that folder. Autosave keeps
updating its allocated file. Earlier recordings are retained.
Controls for changing these variables will be added later.

Named saved files change only when you use Save Log. Every 500 ms, changed
documents are queued for background automatic saving; they are also queued when
closing and flushed at normal shutdown. The status bar shows the automatic
filename. Saving over an automatic file is blocked. File replacement uses a
temporary file and an atomic move when supported.

Save Log captures the current event history and returns immediately while a
worker writes it. You can keep editing; events added after that snapshot remain
marked as unsaved. Choosing Save while closing waits for successful completion
before removing the tab. Failed saves leave existing files intact and display an
error. Explicit saves are written in order. Pending automatic saves coalesce to
the newest snapshot for each document, so a slow disk does not accumulate a queue
of obsolete copies. Autosave is best-effort: a busy writer may finish later than
the 500 ms scheduling interval. Hard termination can lose events not yet saved.

Legacy text logs with session markers remain readable. Their latest session is
selected by default, or specify its number (starting at 1):

```sh
./run.sh --open older.log 1
```

Saving a legacy log converts its selected session to JSON. Text logs from before
session markers were added cannot be reliably reconstructed. A JSON file holds
one session; its session number is always 1.

## Replay

**Replay current log** replays the current history, including unsaved edits.
Replay viewers open as tabs alongside the documents.
The replay editor wraps text, always shows its vertical scrollbar, and hides its
horizontal scrollbar. The recorded caret and selection remain visible even
when focus moves to playback controls. Font family, size, and line-spacing
multiplier come from the log header. An unavailable font uses Java's fallback.

The recorded editor width/height are reproduced on a fixed-size canvas in the
replay tab. For now the editor starts at the upper-left corner; recorded x/y
remain in the log but are not applied. Enlarge the application to see recordings
wider than the current tab.
New recordings store the visible editor viewport's geometry. The canvas keeps
that geometry when the application is resized.
You can also open a saved log directly for replay:

```sh
./run.sh --replay saved-document.json
./run.sh --replay exp_subj_json_1.json
```

- **Play / Pause** follows recorded timestamp intervals and preserves remaining time.
- **Speed ×** selects 0.25, 0.5, 1 (real time), 2, 4, or 8 times recorded speed.
- **Next edit / Previous edit** applies or undoes one edit plus subsequent caret,
  key, and scroll events, and pauses playback.
- **Stop** halts playback at the current position.
- **Fast forward ×4** selects 4× speed and starts playback.
- **Beginning / End** jump to the start or recorded end, including idle time after
  the last event.
- The timeline slider seeks immediately while dragging and pauses playback.
  Tick labels use `0 (s)`, `10 (s)`, etc., with spacing chosen for the session duration. The slider has padding around its edges.

Keys are shown in the status line rather than injected into Swing, which would
repeat the edits. Scroll positions are clamped when the replay window has a
different available scroll range. Older logs without scroll events default to
(0, 0). Reverse stepping restores text, selection, and scrolling; styled
attributes are not reconstructed because this example uses plain text.

Timing depends on Swing's event loop. Inconsistent edits, invalid caret positions,
unknown event IDs, and backwards timestamps are rejected.

## Internal storage and performance

The recorder stores immutable typed events in an indexed list: document edits,
caret changes, scrolling, and key events each have their own fields. It does not
store formatted log lines or JSON strings. JSON formatting happens when saving,
streaming one event at a time to a buffered writer on a background thread. The
external `[[metadata], [events]]` format is unchanged; removed text is reconstructed
when importing saved JSON, so no extra edit fields are required in the file.

Each edit retains only its inserted and removed text. Replay uses a mutable text
buffer to apply edits forward and undo them backward, with caret/scroll state
stored separately. A full text checkpoint is retained every 256 edits to speed
up seeking. It does not retain a full document copy for each event. The current
display text is cached until another edit changes it.

Memory grows with event count, edit payloads, and sparse checkpoints. Loading
still reads the complete JSON file before converting it into typed events.
Each save captures a copy of the event references and metadata, then rewrites
the complete compatible JSON file in the background. This keeps serialization
and disk I/O off Swing's UI thread, but does not make disk work incremental;
very large histories still take longer to save.

## Code organization

The project stays in one Maven module. Recording, replay and file handling live
in `se.lu.scriptloglite`; Inputlog-compatible analyses live in
`se.lu.scriptloglite.inputlog`. Source folders follow these package names under
`src/main/java`:

| Area | Classes |
| --- | --- |
| Startup | `ScriptLogLite` |
| Workspace and editors | `TabbedApplication`, `DocumentTab`, `EditorSupport`, `ThemeManager` |
| Recording model | `RecordingSession`, `LogEvent`, `EventType`, and the event subclasses |
| Swing event capture | `LoggingFilter` |
| Replay | `ReplayLog`, `ReplayCursor`, `ReplayState`, `ReplayPanel` |
| JSON and saving | `JsonLogCodec`, `RawLogCodec`, `LogFormat`, `Json`, `SaveSnapshot`, `BackgroundSaver` |
| Directories and identifiers | `RecordingPaths`, `RecordingVariables`, `DirectoryHistory` |
| IDFX conversion (`se.lu.scriptloglite`) | `InputlogImporter`, `InputlogExporter` |
| Analyses (`se.lu.scriptloglite.inputlog`) | `GeneralAnalysis`, `SummaryAnalysis`, `SNotationAnalysis`, `WordPausesAnalysis`, `LinearAnalysis`, `PauseAnalysis`, `RevisionHistory`, report/panel classes, `InternalGeneralEvents` |

`RecordingSession` owns event history, metadata, revisions, and snapshots.
History and metadata are private, with immutable copies provided to readers.
`LoggingFilter` captures Swing events and forwards them to that session.
`ReplayCaret` and `SpacedTextArea` remain implementation details nested inside
`ReplayPanel`. Regression checks are in `src/test/java`, outside the application
artifact.

The analysis package consumes the shared typed events and replay model through
public entry points and final event fields. Replay's mutable text buffer remains
internal; analyses can read its length or a reconstructed text snapshot. Analysis
results and calculation helpers stay package-private except for the entry points
used by the application and CLI. `KeyPairs` stays in the core package because IDFX
export and analyses share it. Analysis regression checks follow the new package;
a test-only `AnalysisTestSupport` bridge exercises JSON/raw codec internals without
making the codecs part of the public application API.

## NetBeans

NetBeans [recognizes existing Maven projects](https://netbeans.apache.org/wiki/main/wiki/MavenBestPractices/).
Use **File → Open Project** and select this repository's root directory containing
`pom.xml`. No separate Ant project or GUI-builder `.form` files are needed.

- Choose a JDK 11 or newer as the project's Java platform.
- **Run Project** starts `se.lu.scriptloglite.ScriptLogLite`.
- **Debug Project** starts the same application with the NetBeans debugger attached.
- **Test Project** runs the JUnit regression suite through Maven.
- **Clean and Build** produces the application JAR and its runtime libraries.

`nbactions.xml` supplies the Run/Debug mappings, and the POM declares the main
class, Java release, dependencies, and test configuration. Source Packages and
Test Packages appear separately. To run with `--open`, `--replay`, or `--demo`,
set the application arguments in **Project Properties → Run** (or the matching
Run/Debug action's `exec.appArgs` property). The working directory is the project
root; recordings still go to the user's `ScriptLogLiteWD` directory.

Maven resolves FlatLaf and JUnit from Maven Central on the first build. Nimbus
remains the default. FlatLaf is available in the theme menu when Maven supplies
its runtime dependency.

## Build and verification

The shell launcher now compiles all application sources before running:

```sh
./run.sh
./run.sh --open saved-document.json
./run.sh --demo
./run.sh --self-test
```

It uses the JDK compiler API, so it does not require Maven. `--self-test` also
compiles the standalone test harness and runs it headlessly. For optional FlatLaf
checks, use `./run.sh --with-flatlaf --self-test`.

For Maven/NetBeans builds:

```sh
mvn test
mvn package
java -jar target/scriptloglite-1.0-SNAPSHOT.jar
```

Keep `target/lib` alongside the JAR: its manifest references FlatLaf there.
The application JAR accepts `--open`, `--replay`, `--export-idfx`, `--export-webscriptlog`, `--general-analysis`, `--summary-analysis`, `--s-notation`, `--word-pauses`, `--linear-analysis`, `--pause-analysis`, and `--demo`; the regression
suite is separate and runs with `mvn test` or `./run.sh --self-test`.

Checks cover editing, Unicode/escaping, forward/backward replay, timing/speeds,
save/open/continue, scrolling, invalid JSON, and field-for-field round trips of
all 1,259 events in the supplied samples (when present), including exact raw event-line round trips. They also cover independent
tabs and automatic logs, replay controls and geometry, directory persistence,
recording numbering and collisions, 2,048 reversible edits with sparse
checkpoints, background-save coalescing, and failed-save recovery.

Maven compiles against the Java 11 API using `--release 11`. The shell compiler
helper does the same on a complete JDK. If a stripped runtime lacks `ct.sym`, it
reports that only Java 11 syntax and class-file compatibility can be checked.

## Inputlog IDFX import

**File → Open Log** also reads Inputlog `.idfx` files. It reconstructs their text
and event history in a recording tab, where you can continue writing, replay,
and save using the selected JSON/raw formats. **View → Open Log for Replay**
opens the same imported history directly in a replay tab. Command-line examples:

```sh
./run.sh --open JF_92.idfx
./run.sh --replay JF_92.idfx
```

The importer uses Inputlog's Word positions and document lengths, its keyboard
`replay` flag, selections, replacements, and before/after insertion candidates.
Press and release times become ScriptLogLite keyboard events; overlapping
releases are ordered by time. Each imported edit stores the text it removes,
so backward stepping works without Word or an external document. Word paragraph
marks become LF newlines, excluding Word's mandatory final paragraph mark.

`JF_92.idfx` reconstructs 209 UTF-16 characters, with `KJELL HÖGLUND` moved from
the bottom to the top. Excluding newlines, its 200 characters (171 excluding
spaces too) agree with the final Word statistics. This is its own writing
session; its events are not mapped to the `exp_subj` samples. Its cut event
requires an inferred selected-range deletion, checked against Ctrl+X and the
following document length. Its paste candidate is resolved using the preceding
caret and resulting position. These decisions are listed in the import report.

An import report is shown when opening IDFX through the file dialog. It is also
saved under `inputlogImportReport` in JSON/raw headers, alongside the original
Inputlog metadata, session fields, source filename, and mouse/focus/statistics
records. Screen coordinates do not establish editor scroll positions, so those
source events are not replayed as scrolling. The file provides no font, line
spacing or editor geometry; ScriptLogLite uses its defaults. Untimed Word events
are anchored to the preceding operation, selection direction is approximated,
and zero/missing release times do not generate invented releases. Session timing
includes trailing idle time; continuation starts after that interval.

This is an initial text-only importer, tested against the supplied file and
small synthetic histories. It does not reconstruct Word formatting, tables,
images, or documents with unlogged initial text. Unknown event types, inconsistent
document lengths, and ambiguous paste candidates fail with an event-specific
error instead of silently producing an unreliable document. Imported IDFX sessions can be saved as ScriptLogLite JSON/raw or exported to
IDFX, with `JF_92.json`/`JF_92.txt`/`JF_92.idfx` as the corresponding filenames.
The local C# reference reader is in `InputlogHTML/Core/IO/XML/Input`; relevant
Word capture and revision code is in `Core/Plugin/WordLog` and `Core/Analyses/Revision`.


## Inputlog IDFX export

Enable **Settings → Save formats → Inputlog IDFX** to include `.idfx` in manual
and automatic background saves. JSON remains the default. Select IDFX alone to
save only that format, or combine it with JSON/raw. A direct conversion command
reads either JSON or raw without starting the GUI:

```sh
./run.sh --export-idfx exp_subj_json_1.json exp_subj_export_1.idfx
./run.sh --export-idfx exp_subj_raw_1.txt /tmp/from-raw.idfx
```

The supplied `exp_subj_export_1.idfx` was converted from the JSON sample. Its
native fields contain 321 keyboard events (262 replayable typing/backspace
operations), 20 Word replacements, selections, document focus anchors, and
approximate statistics. Press/release pairs become one Inputlog keyboard event;
missing release times are zero. Straightforward single-character typing and
backspace consume their matching document edit, preventing double insertion.
Other edits use explicit Word replacement ranges, including paste and deletion.
LF newlines become Word CR paragraph marks. Initial content is reconstructed by
a synthetic insertion. Word document lengths include the mandatory final mark.

`__ScriptLogLiteExporter` identifies the producing application; the native
program-version field identifies the compatibility schema. The synthetic
relative clock starts at 1 millisecond. The export report identifies inferred
key characters/modifiers, approximate statistics, unknown wall-clock dates and
missing Word layout/document information. This creates an IDFX text history,
not a corresponding `.docx` file.

Original header data is stored in `__ScriptLogLiteHeader`. One structured
`ScriptLogLite` label per event retains nanosecond timing, caret direction and
viewport offsets. These use Inputlog's existing metadata/label mechanisms.
Scroll changes are represented by labelled, unchanged selections, since viewport
pixels cannot establish actual mouse coordinates or wheel deltas. Inputlog
itself sees ordinary selection events; ScriptLogLite restores the viewport when
reading these extensions. Key/release timing has millisecond resolution in
native fields. Word-only edit times require the labels for precise replay.

Tests reconstruct the sample from both the labelled file and a copy with **all
ScriptLogLite extensions removed**, check paired keys, escaping, initial text,
and backwards replay. Final text is preserved in both cases. This environment
cannot run the Windows/Word-based Inputlog application, so opening the supplied
export there is the next interoperability check. IDFX conversion preserves the
text history but does not promise identical Word analysis results or a lossless
round trip of every ScriptLogLite event field.


For IDFX files intended primarily for Inputlog analysis, uncheck
**Settings → Include ScriptLogLite labels in IDFX**. The preference persists and
applies to both manual and automatic background saves, including open documents.
Labels remain enabled by default. Disabling them omits all ScriptLogLite event
labels, header/provenance extensions, and viewport-only selection placeholders.
Standard Inputlog edits, keys, selections and statistics remain. JSON/raw saves
retain their normal information. Without the extensions, IDFX reimports have
native millisecond/preceding-event timing and cannot restore font/geometry,
selection direction or viewport offsets.

The command-line equivalent is:

```sh
./run.sh --export-idfx exp_subj_json_1.json output.idfx --no-lite-labels
```


## General Analysis and conversion validation

Open any IDFX, JSON or raw log (or select a recording/replay tab), then choose
**Analysis → General Analysis…**. The default is **Internal events / reconstructed
text**. The table is calculated from typed ScriptLogLite events and replayed text;
it does **not** export/reimport IDFX or read retained source keyboard/edit payloads
for calculation. Positions, document lengths and cumulative character production
come from the actual edits. Matching nearby typing/backspace edits are combined
with their key press; paste, replacements and other edits have their own rows.
Viewport changes appear as `scrollChange` rows, with viewport X/Y offsets.

The mode selector also offers **Retained Inputlog source events**, preserving the
previous calculation for comparison with Inputlog conventions. It requires
source provenance and describes the imported session only. For a continued log,
it explicitly excludes the later native events; internal mode includes them. **Compare Inputlog HTML…** lists every difference, and **Save HTML
report…** saves the table and comparison. These operations run in background workers.

Command-line examples (without starting the GUI):

```sh
./run.sh --general-analysis JF_92.idfx JF_92_sll_internal_GA.html \
  --compare JF_20261008_92_GA.html
./run.sh --general-analysis JF_92.idfx JF_92_sll_source_GA.html \
  --source-events --compare JF_20261008_92_GA.html
./run.sh --general-analysis exp_subj_json_1.json /tmp/json-GA.html
./run.sh --general-analysis exp_subj_raw_1.txt /tmp/raw-GA.html
```

Source mode matches the supplied reference's **331 rows × 20 columns** exactly.
That establishes compatibility of those source-based calculations, not correctness
of the imported internal history by itself. In internal mode the same session has
**330 rows**: 329 match reference rows by type/output/start time, two reference rows
have no exact counterpart and one generated row is unmatched. The Word no-op
replacement contributes no internal edit. The cut is represented as the actual
selected-range deletion `[195:209]`, rather than Word's `[195:196]` newline record.
Native event IDs, actual reconstructed positions/lengths, edit timestamps and
production counts can also differ from Inputlog's reported/backfilled values.
These differences are visible in the comparison; the internal mode does not copy
source values to obtain an exact match.

Both modes group modifier keys, omit navigation repeats without recorded releases,
use millisecond timings from the first observed action, and provide one-minute and
ten-way interval columns. Document lengths/production include a virtual final
paragraph mark for Inputlog comparison; the live text does not. Source mode also
reproduces Inputlog's legacy position/document-length backfill. Pause-location
rules currently cover the supplied session's text/control/paragraph patterns;
they are not the full configurable Inputlog finite-state machinery.

A separate conversion audit checks retained source records against the internal
replay: pre-edit document lengths, key values, press/release times and replayable
edit positions/text. All **335 JF_92 keyboard records** pass. Mouse/focus timing and
screen coordinates, when available, are included from `inputlogAncillaryEvents`
because the internal text events do not encode those observations. Source keyboard,
replacement and insertion records never supply the internal table's text/positions.

Typed key events now have an optional `strokeId`, preserved by JSON/raw saves and
clock rebasing. IDFX imports retain known press/release associations directly in
these typed events; new Swing recordings assign IDs as they record keys. This
prevents missing-release auto-repeat events from stealing later key releases.
Older files remain readable; without IDs, pairing uses the latest pending press
with the same code/location and is an inference. A missing release gives a zero
compatibility action duration, indicating unavailable timing rather than an
observed instantaneous action. IDFX export uses the same pairing mechanism.
Reimport the original IDFX to obtain these IDs for an older imported JSON/raw log.

Tests establish that internal results are identical after JSON/raw round trips,
that the native `exp_subj` JSON/raw samples produce the same table, and that changing
or removing source keyboard/edit metadata cannot change the internal table. They
also deliberately alter actual key/edit data and verify that it affects analysis
or triggers the independent audit. Matching one reference and passing these checks
is evidence for this session, not proof for every possible IDFX command or analysis.
The `exp_subj` and `JF_92` sessions remain separate writing sessions.

## Summary Analysis (PT0)

Select a document or replay tab and choose **Analysis → Summary Analysis (PT0)…**.
The default calculation uses internal typed events and reconstructed text, and
works with JSON, raw and IDFX logs. The source-mode selector, reference comparison
and HTML export work like General Analysis. Calculation, comparison and saving
run in background workers.

The report implements all **31 process-information and process-time metrics** in
`JF_20261008_92_SU_PT0.html`: typed and inserted/replaced characters, modifier-state
counts, rates, word/sentence/paragraph means, medians and deviations, and total
process duration. PT0 means a pause threshold of zero; pause/burst modules for
other thresholds are not implemented. Nonzero-threshold references are rejected.
Metadata and additional reconstructed-product measures are displayed separately
and excluded from reference comparisons. Analysis creation time belongs to the
new report rather than being copied from the reference.

```sh
./run.sh --summary-analysis JF_92.idfx JF_92_sll_internal_SU_PT0.html \
  --compare JF_20261008_92_SU_PT0.html
./run.sh --summary-analysis JF_92.idfx JF_92_sll_source_SU_PT0.html \
  --source-events --compare JF_20261008_92_SU_PT0.html
./run.sh --summary-analysis exp_subj_json_1.json /tmp/json-summary.html
./run.sh --summary-analysis exp_subj_raw_1.txt /tmp/raw-summary.html
```

These are **process** measures: deleted typing still counts, and pasted text is
separate. The reference's “Total Words in Main Document” is a process count of
completed word units, not the final text's word count. In the supplied session,
both modes obtain 253 typed characters including whitespace, 206 excluding it,
40 process words, 7 paragraphs, 14 pasted characters, and 116.812 seconds.

Internal mode matches **28 of 31** metrics. Its actual cut is a deletion, so it
counts zero replaced characters and 286 total keystrokes/inserted/replaced units,
versus Inputlog's one replaced paragraph mark and total 287. Source mode preserves
that convention and matches **30 of 31**. Both modes calculate a words-per-paragraph
deviation of **2.433**, while Inputlog's supplied report leaves that field blank.
The process paragraph word counts here are `[6, 5, 10, 8, 4, 5, 2]`. This discrepancy
is exposed rather than forcing a blank result; it may reflect Inputlog's boundary
callback ordering or a different analysis build, and is not established as an
Inputlog bug.

The boundary scanner is an independent implementation with whitespace/word
punctuation boundaries, returns and focus changes for paragraphs, and terminal
punctuation followed by whitespace/end or focus changes for sentences. It does
not implement Inputlog's entire configurable finite-state rule set. Character
unit categories follow Inputlog-style lexical conventions; total typed counts
use Unicode letter/number/punctuation and whitespace categories. Compatibility
means intentionally use the total typed count, while medians use lexical unit
lengths without whitespace. Deviations use population variance with Inputlog's
count-greater-than-two and blank-for-zero conventions; paragraph character
variance retains its legacy total-typed-non-whitespace sum. The supplied fixture
is read only by the comparison, never during calculation.

Reconstructed-product statistics use replayed text and actual edits: final UTF-16
length (including LF), non-whitespace length, Unicode word tokens, nonempty
paragraphs and total inserted/removed UTF-16 units. Word page/line statistics are
not guessed. Mouse/focus context retained during IDFX import supplies timing and
main-document focus; its absence in native logs cannot be inferred. Internal mode
does not use retained source keyboard/edit payloads or Word statistics, and it
includes native edits made after an imported session; source mode describes only
the imported prefix.

Regression checks compare every reference metric, verify identical results after
JSON/raw round trips, remove/poison source keyboard/edit provenance, exercise native
continuation, synthetic typing/sentence/paragraph boundaries and programmatic edits,
check comparison error handling, and construct the Summary tab headlessly.

## S-Notation and Word Pauses

Select a recording or replay tab and choose **Analysis → S-Notation…** or
**Analysis → Word Pauses…**. The report tab can switch between these two views,
compare an Inputlog SN/WP HTML report, and save standalone HTML. Calculation and
file operations run in background workers. Both analyses use internal typed
events and reversible edits, so IDFX, JSON and raw files work directly.

`SNotationAnalysis` builds a `RevisionHistory` of live character identities,
deletion tombstones and revision breaks. `WordPausesAnalysis` uses that same
structured history. Notation is rendered for display; neither reconstruction nor
word timing parses the notation string. Every removed span is checked against
live characters, and the final projection must equal the replay's final text.
Literal brackets, braces, pipes, backslashes and middle dots cannot affect the
projection. Consecutive backspaces form one revision; normal continued typing is
unmarked; displaced typing and bulk insertions use braces. Replacement insertion
and deletion share an ID. Later edits can split an insertion into labelled
fragments. This is an S-Notation-style revision representation with local grouping
and numbering conventions, not a byte-for-byte reproduction of Inputlog markup.

For `JF_96.idfx`, the imported and analysed text is exactly
`one two three four five six`, with six tokens. The selected `three `, including
its trailing space, is removed once and inserted once. There are two revision
groups, rather than Inputlog's phantom replacement with `s`. For `JF_92.idfx`,
`KJELL HÖGLUND` appears only at the new location at the top; the leftover title at
the old location in Inputlog's report does not appear. The final 209 UTF-16 units
and 36 non-whitespace tokens come from replay.

When a Ctrl+X deletion is followed by a Ctrl+V insertion containing exactly the
same text, the history preserves original character timing through an **inferred
clipboard lineage**. Original typing time and later placement time are separate.
Repeated pastes preserve lineage without mutating the original deletion record;
an observed Ctrl+C invalidates the previous cut candidate. Menu/externally modified
clipboard contents cannot be established from a keystroke log. Unlinked paste and
other bulk edits have observed placement times and unavailable typing times.

The Word Pauses table contains the familiar Inputlog column labels, followed by
final text coordinates, placement times, provenance and timing status. Definitions
are explicit in every report:

- One row per final non-whitespace token, including attached punctuation. `#Chars`
  is its UTF-16 length; final positions use an exclusive end.
- Start/End WordID identify internal key-event indices, not Inputlog IDs.
- Start WordTime is the earliest surviving original character press; End WordTime
  is the latest release. Word Prod spans these observations when every character
  has complete typing timing. Deleted attempts remain in the revision ledger and
  notation, and are not silently folded into surviving-character timing.
- Within Word sums start-to-start intervals in final character order; it is zero
  for a one-character token and unavailable if that order reverses in time.
- BfrWord-2 is the preceding token's AfterWord gap when the words remain
  chronological. BfrWord-1 is the first press minus preceding whitespace press.
  Btwn Word is the first press minus preceding last-character press. AftWord+1
  is the following whitespace press minus the last-character press.
- Unknown or negative intervals display `-`. Zero is valid for a press at the
  analysis origin. Before-word fields of the first token are zero by convention.
  No fake final return, per-character paste keystrokes, absolute-clock values or
  negative-duration corrections are introduced to fill missing data.

These corrected, documented definitions intentionally differ from Inputlog's
special handling of initial/trailing deletions, one-letter tokens and missing
boundaries. Target-list matching is not implemented; its column stays empty.
The report therefore does not claim full timing equivalence with Inputlog.

```sh
./run.sh --word-pauses JF_96.idfx JF_96_sll_WP.html \
  --compare JF_20261009_96_WP.html
./run.sh --s-notation JF_96.idfx JF_96_sll_SN.html \
  --compare JF_20261009_96_SN.html
./run.sh --word-pauses JF_92.idfx JF_92_sll_WP.html \
  --compare JF_20261008_92_WP.html
./run.sh --s-notation JF_92.idfx JF_92_sll_SN.html
./run.sh --word-pauses exp_subj_json_1.json /tmp/native-WP.html
./run.sh --s-notation exp_subj_raw_1.txt /tmp/native-SN.html
```

Reference comparison reads only the supplied report. It compares reconstruction
after whitespace normalization, matches repeated token spellings by occurrence
in final order, and lists differences for token lengths and eight timing columns.
Notation numbering, revision labels, IDs and target fields are excluded. Inputlog
SN-only references have no word table; the projection of their markup is a
diagnostic comparison only, never an input to calculation. Both supplied WP
reports and the JF_96 SN report expose differing reconstruction. The checks do
not force agreement with those known incorrect results.

Regression checks cover both IDFX sessions, the original keyboard document-length
audit, JSON/raw persistence, native selections and moves, repeat paste, clipboard
invalidation, nested deletions, literal markup, missing releases, bulk edits,
preexisting text, 240 randomized insertion/deletion/replacement sequences and a
headless report tab. Inputlog's C# code and the supplied reports are unchanged.

`JF_97.idfx` adds a linear-writing regression with backspaces and a late deletion
of an early typo. Its reconstructed text agrees with `JF_20261009_97_WP.html`
after whitespace normalization, and all **66 final words** match. There are 23
revision groups; the last removes `w` at offset 61 from the earlier `Princetown.`.
All **445 document keyboard records** pass the independent conversion audit.
The session also includes **21 coordinate-less keyboard events outside Word**.
The importer confirms external focus using timestamps (focus notifications may
appear later in source order) and retains these keys as ancillary provenance,
without adding them to the document or inventing Word coordinates. Missing
coordinates without confirmed external focus still cause an import error.

The JF_97 WP comparison has **510 equal and 84 different** count/timing cells.
Inputlog additionally produces five standalone punctuation rows and an empty
boundary row. Differences remain explicit: punctuation grouping, whitespace
boundary handling, initial deleted attempts and one-letter timing conventions
do not have identical definitions in these two implementations. JSON/raw round
trips preserve the results and external activity. This fixture validates the
reconstruction without claiming full Inputlog timing equivalence.

## Linear Analysis: chronological writing score

Choose **Analysis → Linear Analysis…** from a recording/replay tab. The compact
score follows writing actions in time, including corrections and navigation,
rather than displaying only the final text. The default is internal events;
retained Inputlog source mode is also available. Set the two parameters and press
**Update score**:

- **PT (ms)**: pause threshold in milliseconds, default **200**. Positive gaps at
  or above the threshold display as blue `{milliseconds}` marks. PT=0 shows all
  positive gaps; it does not manufacture `{0}` pauses.
- **FL (s)**: fixed interval length in seconds, default **60**. Events are grouped
  into `[start,end)` intervals from the first observed action. An event at exactly
  60 seconds belongs to the next row. The UI accepts 1–86400 seconds; the CLI
  supports larger values within the timestamp range.

Typed characters appear directly, spaces as `·`, special keys/navigation as gray
`[KEY]`, and bulk insertions as green `<text>`. Native unassociated deletions and
replacements display their actual ranges. Key releases, caret markers and typing
edits already associated with a key are omitted to avoid duplicated writing.
Focus observations affect timing without displaying long window titles; retained
mouse actions and native viewport changes remain visible. Literal notation
delimiters are escaped, and HTML output escapes document text.

Keyboard gaps use start-to-start timing. After mouse/viewport events they use the
preceding end; focus updates the timing anchor. Standalone native edits also have
timed gaps. The first mouse action's duration is displayed using Inputlog's
initial-action convention; it is not idle time before recording. Overlaps produce
zero idle gap in internal mode. Interval labels are elapsed times, rather than
the supplied report's Windows-clock values. Long empty intervals are summarized
as gaps, so a long idle session does not allocate millions of empty score rows.
Final reconstructed text is presented separately from the score.

**Compare Inputlog HTML…** validates that PT/FL agree with the reference and
compares every score row, ignoring interval clock labels and invisible zero-width
space formatting. **Save HTML report…** exports the score, parameters, notes and
optional reference comparison. Calculation, comparison and saving run in
background workers.

```sh
./run.sh --linear-analysis JF_92.idfx JF_92_sll_LA_PT200_FL60.html \
  --pt 200 --fl 60 --compare JF_20261008_92_LA_PT200_FL60.html
./run.sh --linear-analysis JF_92.idfx JF_92_sll_source_LA_PT200_FL60.html \
  --source-events --pt 200 --fl 60 --compare JF_20261008_92_LA_PT200_FL60.html
./run.sh --linear-analysis exp_subj_json_1.json /tmp/native-linear.html --pt 500 --fl 30
./run.sh --linear-analysis exp_subj_raw_1.txt /tmp/native-linear-raw.html --pt 500 --fl 30
```

Source mode matches both score rows in `JF_20261008_92_LA_PT200_FL60.html` exactly
after excluding the formatting/clock labels described above. It preserves legacy
rendering conventions: no replacement output, hidden unreleased arrow output,
only the first timing observation in an unreleased arrow run, and a pause crossing
an interval boundary remains in the preceding row. Internal mode preserves every
observed repeat, shows actual range edits, clamps overlapping gaps to zero, and
puts a pause in the arriving action's interval. These differences are exposed by
the comparison. Source mode includes retained external keyboard activity and only
the imported prefix; internal mode describes document events and the full current
history, with coordinate-less external keys excluded.

Regression checks establish source-fixture agreement and internal JSON/raw
equivalence, verify threshold inclusion and exact interval boundaries, explicit
edits and continuation, source-payload independence, mismatched parameter errors,
literal HTML handling, empty histories and sparse long intervals. Only fixed-length
interval splitting is implemented; Inputlog's focus/revision/pause-based splitting
and condensed secondary score are separate features.


## Pause Analysis: pause distributions and P-bursts

Choose **Analysis → Pause Analysis…** from a recording or replay tab. Calculations
use internal events by default and work for IDFX, JSON and raw logs. The report
includes process/pause/active-writing time, pause counts and arithmetic/geometric
means, medians, population deviations, log-transformed 95% confidence intervals,
pause locations, combined boundary pauses, P-bursts, and summaries per interval.
The reconstructed text and a table of counted action gaps accompany the results.

Set parameters and press **Update analysis**:

- **PT (ms)**: minimum pause duration, default **200**, inclusive. PT0 counts only
  positive gaps, not zero-time markers.
- **FN (intervals)**: fixed **number** of equal-duration intervals, default **5**.
  Interval length is process time divided by FN, rounded up to a millisecond.
  Internal intervals use the arriving action's elapsed time, with an event on an
  exact boundary assigned to the next interval. FN may be 1–10000; short/empty
  sessions can have empty intervals.
- **P-burst (ms)**: independent threshold separating activity sequences, default
  **2000**. PT does not filter bursts. Typed whitespace counts as characters;
  pasted text does not. Focus changes reset the burst gap anchor.

Pause gaps use keyboard start-to-start timing, as in Inputlog. After mouse or
viewport actions they use the preceding end time. Actual standalone edits are
included; typing edits associated with key presses are not duplicated. Initial
text does not manufacture activity, and initial delay before the first observed
action cannot be inferred. Pause-location rules use the independently implemented
General Analysis lexical rules rather than Inputlog's configurable FSM. These are
process measures, including subsequently deleted writing, not final-product counts.
Combined boundary pauses are a separate view and must not be added to pause totals.

**Retained Inputlog source events** mode reproduces the supplied
`JF_20261008_92_PA_PT200_FN5.html`: **all 198 metrics agree exactly**, including
151 pauses, 85.345 seconds of pause time and three P-bursts. Calculation does not
read the reference. Compatibility mode retains delayed interval assignment, its
closing-event timing convention, modifier augmentation and small-sample blanks/
zeros. Internal mode uses actual edits, assigns gaps to their arriving interval,
counts each modifier gap once in combined boundaries, clamps overlapping gaps,
and calculates mathematically defined small-sample statistics (a confidence
interval still needs at least two observations). Source mode describes only the
imported prefix; internal mode includes later edits. Missing source provenance is
reported rather than silently substituting internal mode.

**Compare Inputlog HTML…** compares metrics by section and label, including empty
cells, and validates PT, FN and P-burst parameters. **Save HTML report…** saves a
standalone report with optional comparison. Calculations, comparison and saving
run in background workers. Other Inputlog grouping modes and multiple-threshold
runs are not included in this fixed-number implementation.

```sh
./run.sh --pause-analysis JF_92.idfx JF_92_sll_PA_PT200_FN5.html \
  --pt 200 --fn 5 --compare JF_20261008_92_PA_PT200_FN5.html
./run.sh --pause-analysis JF_92.idfx JF_92_sll_source_PA_PT200_FN5.html \
  --source-events --pt 200 --fn 5 --compare JF_20261008_92_PA_PT200_FN5.html
./run.sh --pause-analysis exp_subj_json_1.json /tmp/native-pauses.html --pt 500 --fn 10 --pb 2000
```

Regression checks cover the complete source fixture, internal JSON/raw persistence,
independence from raw source keys/edits, native continuation, parameter mismatches,
thresholds and interval assignments, small-sample statistics and Student-t critical
values, empty sessions and HTML escaping.


### Pause Analysis validation with JF_97

`JF_20261009_97_PA_PT200_FN5.html` exposed simultaneous focus/action timestamps:
source compatibility mode now places focus before an action with the same time.
This removes three spurious counted pauses and corrects the two following mouse
gaps. The supplied Inputlog report and source mode agree on the complete overview,
general statistics, P-bursts and all five interval tables: **273 pauses**, **210.688
seconds** of pause time and **16 P-bursts**. Overall, **144 of 198 metrics match**;
**54 differences remain**, all in pause-location or combined-location tables.
The simpler lexical rules do not implement the full sentence/paragraph FSM. For
example, source mode currently reports two before-paragraph pauses versus three,
and zero combined between-paragraph pauses versus two. This fixture does not
establish full location compatibility.

Internal mode reports **267 pauses** and **193.895 seconds** of pause time. It uses
the document event stream, excluding the 21 coordinate-less external keystrokes
identified in the Word Pauses validation, and its own edit/overlap/closing-gap
conventions. Its totals therefore describe a different stream from Inputlog's
all-activity source analysis. These differences are not evidence of missing
document edits; reconstruction is validated separately by the existing JF_97
import/revision checks. JSON/raw round trips preserve both modes' results.

```sh
./run.sh --pause-analysis JF_97.idfx JF_97_sll_PA_PT200_FN5.html \
  --pt 200 --fn 5 --compare JF_20261009_97_PA_PT200_FN5.html
./run.sh --pause-analysis JF_97.idfx JF_97_sll_source_PA_PT200_FN5.html \
  --source-events --pt 200 --fn 5 --compare JF_20261009_97_PA_PT200_FN5.html
```


## Importing WebScriptLog snapshot logs

**File → Open…** and **File → Replay…** accept WebScriptLog JSON objects, including
`.txt` files such as `wslog_QQQQQQ_09-10-2026_19_54_58.txt`. Detection uses file
contents, not the extension. Open imports a document that can be continued; replay
supports the normal speed controls, seeking and stepping both ways. Save as the
usual ScriptLogLite JSON (default) or raw format to keep working with the converted
history. No separate document file is needed.

The importer merges `text_records`, `cursor_records` and `key_records` by epoch
millisecond timestamps. Each changed full snapshot becomes one reversible
insert/remove/replace using a common prefix/suffix. A simultaneous post-edit cursor
helps disambiguate repeated characters when its proposed range reconstructs the
snapshot exactly. UTF-16 surrogate pairs stay intact. Snapshots precede simultaneous
cursor observations, while keydown precedes and keyup follows them. Cursor pairs
are interpreted as selectionStart:selectionEnd; backward selection direction is
unavailable. Header start/end times are preserved, with a closing view marker to
retain idle time at the end.

Unknown mobile key identities remain `Unidentified`/`undefined`, with key code 0
and their original press/release timestamps. Available Backspace, Enter and Shift
identities are preserved. Snapshot text is not used to invent key identities or
physical key durations. Autocorrections/composition can produce bulk or replacement
edits; the import represents observed changes without guessing how they were made.
Initial text is assumed empty, and the first snapshot establishes the first
observed text; earlier edits cannot be recovered. Unchanged snapshots do not create
fake edits. Source pointer records and any additional nonempty record streams are
retained in metadata, with an import report explaining streams not replayed.

The supplied mobile fixture reconstructs **all 296 snapshots exactly**, preserves
**503 cursor records** and **600 keyboard records** (including **484 unidentified**
records), and retains **14 pointer records** in metadata. Final text is **209 UTF-16
units**, and every edit reverses back to the initial empty state. Saved JSON/raw
logs preserve event times, text and import metadata and can be continued. General,
Linear, Pause and Word Pauses analyses accept the internal history, but keyboard
production measures cannot count unavailable mobile characters; unknown keys and
standalone snapshot edits remain distinct observations. Import geometry/font data
is unavailable in this header, so normal ScriptLogLite defaults apply.

```sh
./run.sh --replay wslog_QQQQQQ_09-10-2026_19_54_58.txt
./run.sh --linear-analysis wslog_QQQQQQ_09-10-2026_19_54_58.txt /tmp/web-linear.html
./run.sh --word-pauses wslog_QQQQQQ_09-10-2026_19_54_58.txt /tmp/web-word-pauses.html
```

Regression checks validate every recorded snapshot, exact observed key identities
and timestamps, reverse replay, JSON/raw saves and continuation, repeated-character
cursor disambiguation, surrogate replacements, unchanged snapshots, malformed
records and the internal analysis entry points.


## JSON/raw edit dialect

Choose **Settings → JSON/raw edit dialect → Replace insertions (length 0)** to
save insertion events as `replace` with length 0. JSON uses the corresponding
replace event ID (103); raw uses `<replace>`. Other replacements and `remove`
events retain their types. The header records `editDialect: "replace"`.
**Replace insertions (length 0)** is the default when no dialect preference has
been saved. **Recorded callbacks** remains selectable and records
`editDialect: "recorded"`. Explicitly saved dialect choices are retained.

The setting is remembered and applies to current and newly opened documents,
manual saves and automatic saves. It also works when converting imported web,
JSON or raw logs through Open and Save As. The captured in-memory history keeps
its original types; serialization changes the vocabulary without changing edit
payloads, timestamps, caret/key records or event count. A reopened replace-dialect
file contains replace events; changing back to Recorded callbacks cannot recover
which of those events originally came from `insertString`. Keep the original log
when original callback provenance is needed. IDFX export retains its existing
mapping and is unaffected by this JSON/raw preference.

No text or timing inference is added, and the conversion streams one event at a
time. Existing loaders already accept zero-length replace events. Regression
checks cover both formats, reverse replay at every event, mobile/native fixtures,
continued writing, matching JSON event IDs, preference persistence and immutable
background-save snapshots.


## Export as WebScriptLog

Use **File → Export as WebScriptLog…** with a document or replay tab selected.
This is an explicit conversion, separate from Save/Save As, automatic recording
and the native Save formats settings. It exports the complete captured history,
not just the current replay position. Export runs in a background worker, writes
atomically and leaves the document's save path, unsaved status and settings intact.

The `.txt` file is a JSON object matching the supplied WebScriptLog structure:
`header_records` contains epoch-millisecond start/end times; `text_records` holds
the full reconstructed text after each edit; `cursor_records` holds normalized
selectionStart:selectionEnd ranges; `key_records` holds browser-style keydown/keyup
names. Nonempty initial text becomes an initial snapshot. Known special keys are
translated to browser names, printable observed characters are retained, and
unavailable identities become `Unidentified`. Imported web pointer records are
retained. Original duplicate caret observations are exported as recorded.

Web dictionaries allow only one record of each stream per millisecond, whereas
native timestamps have finer precision. Colliding timestamps move forward by the
minimum whole milliseconds needed to avoid overwriting records and preserve
reimport order; the header end time extends if necessary. The export report gives
the number of adjusted timestamps, unidentified keys, backward selections whose
direction cannot be represented, and omitted native scroll events. No native
scroll conversion is guessed because the sample's web scroll stream is empty;
retained web scroll/image/window streams pass through. Fonts, styled-text attributes,
stroke IDs, exact native callback vocabulary and explicit modifier fields do not
have equivalents in the supplied web schema.

```sh
./run.sh --export-webscriptlog exp_subj_json_1.json exp_subj_webscriptlog.txt
./run.sh --export-webscriptlog wslog_QQQQQQ_09-10-2026_19_54_58_sll_replace.json mobile_webscriptlog.txt
```

Tests reimport mobile, native JSON and IDFX exports and compare every changed text
snapshot and keyboard-record count. The mobile fixture preserves its original
text/key/pointer records and timestamps exactly, with the imported closing view
marker also exported. Tests cover colliding submillisecond events, nonempty initial
text, selection direction, export limitations, atomic file output and unchanged
native save state. This validates the supplied schema and ScriptLogLite reimport;
it does not imply full native-format provenance survives conversion.
