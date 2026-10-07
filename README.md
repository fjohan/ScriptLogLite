# ScriptLogLite

A Swing text area records document edits, caret movements, key
presses/releases, and scrolling. Requires a JDK (Java 11 or newer). On Linux/macOS, the launcher downloads the
FlatLaf JAR from Maven Central on its first run using `curl`, caches it in `.deps`,
and starts the program. Later runs work offline:

```sh
./run.sh
```

The main `JFrame` contains a `JTabbedPane`. Each document and replay viewer has
its own closable tab. New and Open Log create separate documents with independent
text, selection, scroll position, and logging history. Tabs scroll when there
are too many to fit. A compact toolbar provides New, Open, Save, and Replay.
The application uses [FlatLaf](https://www.formdev.com/flatlaf/) 3.7. Choose
**View → Theme → Light / Dark** to change the entire workspace while it is open.
Light is the default at startup. Switching preserves document text, selection,
logging history, and replay position. The editor uses a larger font and padding.
Theme choices apply for the current run.

The menus provide:

- **File**: New, Open Log, Save Log, Save Log As, Close, Exit.
- **Edit**: Cut, Copy, Paste, Select All, and example insert/replace/remove commands.
- **View**: Replay Current Log, Open Log for Replay, and Light/Dark themes.
- **Tabs**: Next Tab, Previous Tab, Close All, and a list of open tabs.
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

## JSON log format

Saved logs and the automatic `document-filter.json` use the same structure as
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

Named saved files change only when you use Save Log. Each document also has its
own automatic JSON log. Every 500 ms, changed documents are queued for background
saving; they are also queued when closing and flushed at normal shutdown.
The first document uses `document-filter.json`; additional documents
use `document-filter-<unique-id>.json`. The status bar shows the selected
document's automatic filename. The first automatic file is replaced on a new
run; save a named copy to keep it. Saving over automatic files is blocked. File
replacement uses a temporary file and an atomic move when supported.

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
You can also open a saved log directly for replay:

```sh
./run.sh --replay saved-document.json
./run.sh --replay exp_subj_json_1.json
```

- **Play / Pause** follows recorded timestamp intervals and preserves remaining time.
- **Speed ×** selects 0.25, 0.5, 1 (real time), 2, 4, or 8 times recorded speed.
- **Next edit / Previous edit** applies or undoes one edit plus subsequent caret,
  key, and scroll events, and pauses playback.
- **Restart** returns to the initial state.

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

## Verification

```sh
./run.sh --demo
java -Djava.awt.headless=true src/main/java/se/lu/scriptloglite/ScriptLogLite.java --self-test
```

Checks cover editing, Unicode/escaping, forward/backward replay, timing/speeds,
session selection, save/open/continue, scrolling, invalid JSON, and round trips
of all 1,259 events in the supplied sample (when present). Tabbed-workspace checks cover
independent documents and automatic logs, opening additional documents, active
menu commands, tab navigation/listing, and closing replay timers. Additional checks
cover 2,048 reversible edits with sparse checkpoints, saving while editing,
automatic-save coalescing, background completion callbacks, and failed-save
recovery. The sample's JSON event objects are compared field-for-field after
export.

The main class is `se.lu.scriptloglite.ScriptLogLite`. You can also build with
Maven (requires a JDK with `javac` and Maven installed):

```sh
mvn package
java -jar target/scriptloglite-1.0-SNAPSHOT.jar
```

Keep `target/lib` alongside the JAR: its manifest references FlatLaf there.
The JAR accepts the same `--open`, `--replay`, `--demo`, and `--self-test` arguments.
For example, run all checks including real FlatLaf theme switches with:

```sh
java -Djava.awt.headless=true -jar target/scriptloglite-1.0-SNAPSHOT.jar --self-test
```

Without Maven, you can download `flatlaf-3.7.jar` into `.deps` manually and use:

```sh
java --class-path .deps/flatlaf-3.7.jar src/main/java/se/lu/scriptloglite/ScriptLogLite.java
```

The dependency-free headless source command above still checks logging and
replay; it reports that theme checks are skipped if FlatLaf is absent. To include
those checks without Maven, use `./run.sh --self-test` (with
`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true` if no display is available).
