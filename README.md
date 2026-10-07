# ScriptLogLite

A standalone Swing text area records document edits, caret movements, key
presses/releases, and scrolling. Requires a JDK (Java 11 or newer):

```sh
java src/main/java/se/lu/scriptloglite/ScriptLogLite.java
```

The main `JFrame` contains a `JTabbedPane`. Each document and replay viewer has
its own closable tab. New and Open Log create separate documents with independent
text, selection, scroll position, and logging history. Tabs scroll when there
are too many to fit. A compact toolbar provides New, Open, Save, and Replay.
The application uses the platform's system look and feel, with a larger editor
font and padding.

The menus provide:

- **File**: New, Open Log, Save Log, Save Log As, Close, Exit.
- **Edit**: Cut, Copy, Paste, Select All, and example insert/replace/remove commands.
- **View**: Replay Current Log and Open Log for Replay.
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
java src/main/java/se/lu/scriptloglite/ScriptLogLite.java --open saved-document.json
java src/main/java/se/lu/scriptloglite/ScriptLogLite.java --open exp_subj_json_1.json
```

Named saved files change only when you use Save Log. Each document also has its
own automatic JSON log, refreshed every 500 ms, when closing, and at normal
shutdown. The first document uses `document-filter.json`; additional documents
use `document-filter-<unique-id>.json`. The status bar shows the selected
document's automatic filename. The first automatic file is replaced on a new
run; save a named copy to keep it. Saving over automatic files is blocked. File
replacement uses a temporary file and an atomic move when supported.

Legacy text logs with session markers remain readable. Their latest session is
selected by default, or specify its number (starting at 1):

```sh
java src/main/java/se/lu/scriptloglite/ScriptLogLite.java --open older.log 1
```

Saving a legacy log converts its selected session to JSON. Text logs from before
session markers were added cannot be reliably reconstructed. A JSON file holds
one session; its session number is always 1.

## Replay

**Replay current log** replays the current history, including unsaved edits.
Replay viewers open as tabs alongside the documents.
You can also open a saved log directly for replay:

```sh
java src/main/java/se/lu/scriptloglite/ScriptLogLite.java --replay saved-document.json
java src/main/java/se/lu/scriptloglite/ScriptLogLite.java --replay exp_subj_json_1.json
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

Timing depends on Swing's event loop. Replay loads text snapshots into memory,
so large histories can use substantial memory. Inconsistent edits, invalid caret
positions, unknown event IDs, and backwards timestamps are rejected.

## Verification

```sh
java src/main/java/se/lu/scriptloglite/ScriptLogLite.java --demo
java -Djava.awt.headless=true src/main/java/se/lu/scriptloglite/ScriptLogLite.java --self-test
```

Checks cover editing, Unicode/escaping, forward/backward replay, timing/speeds,
session selection, save/open/continue, scrolling, invalid JSON, and round trips
of all 1,259 events in the supplied sample (when present). Tabbed-workspace checks cover
independent documents and automatic logs, opening additional documents, active
menu commands, tab navigation/listing, and closing replay timers.

The main class is `se.lu.scriptloglite.ScriptLogLite`. Alternatively compile and
run it from the repository root:

```sh
javac -d out src/main/java/se/lu/scriptloglite/ScriptLogLite.java
java -cp out se.lu.scriptloglite.ScriptLogLite
```

The compiled program accepts the same arguments.
