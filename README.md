# DocumentFilter logger

A standalone Swing example that logs every invocation of `insertString`,
`replace`, and `remove` on its document filter to the console and to
`document-filter.log` in the current directory. Each entry includes a UTC
timestamp, method, offset, removed length, replacement text, and attributes.
Edit entries also include `oldText`, the removed text needed for replay.
Entries are appended and flushed immediately. The filter allows edits through.

The text area's `CaretListener` also logs each `caretUpdate` event to the same
outputs, including `dot` (the caret position), `mark` (the selection anchor),
and the selection start and end. Equal dot and mark values mean no selection.

A `KeyListener` logs `keyPressed` and `keyReleased` events received by the text
area, including the key code, readable key name, character (or `undefined`),
modifiers, and key location. These events are logged when the text area has
keyboard focus; the buttons' programmatic edits do not generate key events.

Requires a JDK (Java 11 or newer). Run directly from source:

```sh
java DocumentFilterLogger.java
```

Type, paste, or delete in the text area. The buttons explicitly call each
document method: insert adds `Hello` at the caret; replace substitutes the
selection with `World`; remove deletes the selection or the next character.
Swing may use `replace` when typing, so user actions do not necessarily map
one-to-one to the three method names. Zero-length document operations may be
ignored before reaching the filter. The log records filter invocations before
delegating the edits, rather than confirming successful changes.

To exercise all three methods plus caret movement and selection without a
graphical display:

```sh
java DocumentFilterLogger.java --demo
```

Alternatively compile with `javac DocumentFilterLogger.java`, then run
`java DocumentFilterLogger` (optionally with `--demo`).

Use **Save Log…** to save the current session's complete history to a chosen
file. Use **Open Log…** to reconstruct the latest session from a saved log,
including its text, caret, and selection, and continue editing. No separate
document file is needed. Saving again preserves the original events and adds
the new ones, so backward replay still works across opening and continuing.
Opening replaces the current session after confirmation; save it first if you
want to keep it. Saving an existing file asks before replacing it.

You can also open a log for editing from the command line, optionally choosing
a session number:

```sh
java DocumentFilterLogger.java --open saved-document.log
java DocumentFilterLogger.java --open saved-document.log 1
```

**Replay current log** opens a replay window for the current session, including
unsaved edits. Restoring a saved log does not record synthetic edits. Named
logs are updated when you use Save Log; the automatic `document-filter.log`
continues recording separately, with a new checkpoint when you open a log.
Choose another filename when saving to avoid overwriting that active file.

Replay a recorded session in a separate, read-only window:

```sh
java DocumentFilterLogger.java --replay document-filter.log
```

The controls provide:

- **Play / Pause**: replay events using their recorded timestamp intervals.
- **Speed ×**: choose 0.25, 0.5, 1 (real time), 2, 4, or 8 times recorded speed.
  Speed changes take effect during playback, and pause preserves remaining time.
- **Next edit / Previous edit**: apply or undo one edit, including the caret and
  selection events following that edit. Stepping pauses playback.
- **Restart**: return to the session's initial state.

Timed playback also restores caret events and shows key events in the status
line. Keys are displayed rather than injected into Swing, which would duplicate
the recorded document edits. Playback never writes to the recording log.
Timing follows Swing's event loop, so it is approximate rather than a hard
real-time guarantee. Reverse steps restore text and selection snapshots; styled
attributes are not reconstructed because this example uses a plain text area.

Each new recording starts with a session marker. Replay selects the latest
session by default. To select an earlier session, supply its number (starting
at 1 for sessions recorded with this version):

```sh
java DocumentFilterLogger.java --replay document-filter.log 1
```

Older entries without session markers are ignored. Logs recorded before replay
support cannot be reliably replayed because they lack session boundaries and
removed text; make a new recording. Inconsistent edits, invalid caret positions,
and timestamps running backwards are rejected with an event number.

Replay loads the selected session into memory and stores text snapshots for
reverse stepping, so very large logs can use substantial memory. Load a log
after recording finishes to replay a complete session.

Run the headless replay checks (escaped text, forward/backward edits, selection,
session selection, invalid edits, playback speeds, and saving/opening/continuing
with history preserved) with:

```sh
java -Djava.awt.headless=true DocumentFilterLogger.java --self-test
```
