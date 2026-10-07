# DocumentFilter logger

A standalone Swing text area records document edits, caret movements, key
presses/releases, and scrolling. Requires a JDK (Java 11 or newer):

```sh
java DocumentFilterLogger.java
```

Type, paste, delete, or use the insertString/replace/remove buttons. The filter
allows edits through. Swing often calls `replace` for typing. Document operations
that Swing ignores before reaching the filter do not produce filter events.
The console provides diagnostic entries in text form.

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

**Save Log…** saves the complete current history. **Open Log…** restores text,
caret, selection, and scroll position, then lets you continue editing. Saving
again retains earlier events and adds new ones. No separate document file is
needed. Opening asks before replacing the current session, and saving asks
before replacing an existing file.

```sh
java DocumentFilterLogger.java --open saved-document.json
java DocumentFilterLogger.java --open exp_subj_json_1.json
```

Named saved files change only when you use Save Log. The separate automatic
`document-filter.json` is refreshed every 500 ms and at normal shutdown. It holds
the current complete session and is replaced on a new run or when you open
another log. Save a named copy to keep a session. Saving over the active automatic
file is blocked. File replacement uses a temporary file and an atomic move when
the filesystem supports it.

Legacy text logs with session markers remain readable. Their latest session is
selected by default, or specify its number (starting at 1):

```sh
java DocumentFilterLogger.java --open older.log 1
```

Saving a legacy log converts its selected session to JSON. Text logs from before
session markers were added cannot be reliably reconstructed. A JSON file holds
one session; its session number is always 1.

## Replay

**Replay current log** replays the current history, including unsaved edits.
You can also open a saved log directly for replay:

```sh
java DocumentFilterLogger.java --replay saved-document.json
java DocumentFilterLogger.java --replay exp_subj_json_1.json
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
java DocumentFilterLogger.java --demo
java -Djava.awt.headless=true DocumentFilterLogger.java --self-test
```

Checks cover editing, Unicode/escaping, forward/backward replay, timing/speeds,
session selection, save/open/continue, scrolling, invalid JSON, and round trips
of all 1,259 events in the supplied sample (when present).

Alternatively compile with `javac DocumentFilterLogger.java`, then run
`java DocumentFilterLogger` with the same arguments.
