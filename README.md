# DocumentFilter logger

A standalone Swing example that logs every invocation of `insertString`,
`replace`, and `remove` on its document filter to the console and to
`document-filter.log` in the current directory. Each entry includes a UTC
timestamp, method, offset, removed length, replacement text, and attributes.
Entries are appended and flushed immediately. The filter allows edits through.

The text area's `CaretListener` also logs each `caretUpdate` event to the same
outputs, including `dot` (the caret position), `mark` (the selection anchor),
and the selection start and end. Equal dot and mark values mean no selection.

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
