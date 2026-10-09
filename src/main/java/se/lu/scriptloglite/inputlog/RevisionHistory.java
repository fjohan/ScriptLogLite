package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.EditEvent;
import se.lu.scriptloglite.EventType;
import se.lu.scriptloglite.KeyLogEvent;
import se.lu.scriptloglite.KeyPairs;
import se.lu.scriptloglite.LogEvent;
import se.lu.scriptloglite.ReplayLog;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Live character identities and tombstones; notation is an output, never a reconstruction format. */
final class RevisionHistory {
    interface Node { }
    static final class Atom implements Node {
        final char value;
        final long identity, originIdentity;
        final Integer keyIndex;
        final Long start, end;
        final int insertedRevision, movedFrom;
        final Long placement;
        final String provenance;
        Atom(char value, long identity, long originIdentity, Integer keyIndex, Long start, Long end,
                int insertedRevision, int movedFrom, Long placement, String provenance) {
            this.value = value; this.identity = identity; this.originIdentity = originIdentity; this.keyIndex = keyIndex;
            this.start = start; this.end = end; this.insertedRevision = insertedRevision; this.movedFrom = movedFrom;
            this.placement = placement; this.provenance = provenance;
        }
    }
    static final class Deletion implements Node {
        final Revision revision;
        final List<Node> children;
        Deletion(Revision revision, List<Node> children) { this.revision = revision; this.children = children; }
    }
    static final class Break implements Node {
        final int revision;
        Break(int revision) { this.revision = revision; }
    }
    static final class Revision {
        final int id, firstEvent;
        int offset;
        int lastEvent;
        String removed, inserted;
        final long start;
        long end;
        final boolean cut;
        int movedFrom;
        Revision(int id, int offset, int event, String removed, String inserted, long time, boolean cut) {
            this.id = id; this.offset = offset; this.firstEvent = this.lastEvent = event;
            this.removed = removed; this.inserted = inserted; this.start = this.end = time; this.cut = cut;
        }
    }
    final ReplayLog log;
    final List<Node> nodes = new ArrayList<>();
    final List<Atom> live = new ArrayList<>();
    final List<Revision> revisions = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final long origin;
    String text;
    private long nextIdentity = 1;
    private int continuation, activeInsertion;
    private Deletion lastDeletion;
    private int lastDeleteOffset, lastDeleteKey = -1;
    private List<Atom> clipboard;
    private int clipboardRevision;

    RevisionHistory(ReplayLog log) {
        this.log = log;
        long first = Long.MAX_VALUE;
        for (LogEvent event : log.events) if (event instanceof EditEvent || event instanceof KeyLogEvent && event.type == EventType.KEY_PRESSED)
            first = Math.min(first, millis(event));
        // Same zero point as the existing event analysis, including retained mouse/focus observations.
        Object meta = log.metadata.get("inputlogMeta");
        if (meta instanceof Map && ((Map<?, ?>) meta).get("__LogRelativeCreationDate") != null) {
            long creation = Long.parseLong(((Map<?, ?>) meta).get("__LogRelativeCreationDate").toString());
            for (GeneralAnalysis.Source source : GeneralAnalysis.sourceRecords(log.metadata.get("inputlogAncillaryEvents")))
                if ((source.type.equals("mouse") || source.type.equals("focus")) && source.start() > 0)
                    first = Math.min(first, source.start() - creation);
        }
        origin = first == Long.MAX_VALUE ? 0 : first;
        for (char ch : log.initialText.toCharArray()) {
            long identity = nextIdentity++;
            nodes.add(new Atom(ch, identity, identity, null, null, null, 0, 0, null, "initial"));
        }
        continuation = log.initialText.length();
        Map<Integer, Integer> owners = InternalGeneralEvents.linkEdits(log), releases = KeyPairs.pair(log.events);
        KeyLogEvent command = null;
        for (int i = 1; i < log.events.size(); i++) {
            LogEvent event = log.events.get(i);
            if (event instanceof KeyLogEvent && event.type == EventType.KEY_PRESSED) {
                command = (KeyLogEvent) event;
                if (control(command, KeyEvent.VK_C)) { clipboard = null; clipboardRevision = 0; }
            }
            if (!(event instanceof EditEvent)) continue;
            EditEvent edit = (EditEvent) event;
            boolean cut = command != null && control(command, KeyEvent.VK_X) && near(command, edit)
                    && !edit.removed.isEmpty() && edit.replacement().isEmpty();
            boolean paste = command != null && control(command, KeyEvent.VK_V) && near(command, edit);
            Integer keyIndex = owners.get(i);
            KeyLogEvent key = keyIndex == null ? null : (KeyLogEvent) log.events.get(keyIndex);
            int code = key == null ? -1 : key.keyCode;
            long placed = millis(edit) - origin;
            Revision revision = null;
            if (!edit.removed.isEmpty()) {
                int from = boundary(edit.offset), to = boundary(edit.offset + edit.removed.length());
                List<Node> removed = new ArrayList<>(nodes.subList(from, to));
                List<Atom> removedAtoms = atoms(removed);
                if (!plain(removedAtoms).equals(edit.removed)) throw new IllegalArgumentException("Revision removal disagrees with replay at event " + i);
                boolean merge = lastDeletion != null && code == KeyEvent.VK_BACK_SPACE && code == lastDeleteKey
                        && edit.replacement().isEmpty() && edit.offset + edit.removed.length() == lastDeleteOffset;
                nodes.subList(from, to).clear();
                if (merge) {
                    nodes.remove(lastDeletion); removed.remove(lastDeletion);
                    removed.addAll(lastDeletion.children);
                    revision = lastDeletion.revision;
                    revision.offset = edit.offset; revision.removed = edit.removed + revision.removed; revision.lastEvent = i; revision.end = placed;
                } else revision = newRevision(edit.offset, i, edit.removed, "", placed, cut);
                lastDeletion = new Deletion(revision, removed);
                nodes.add(boundary(edit.offset), lastDeletion);
                lastDeleteOffset = edit.offset; lastDeleteKey = code;
                continuation = edit.offset; activeInsertion = 0;
                if (cut) { clipboard = List.copyOf(removedAtoms); clipboardRevision = revision.id; }
            } else { lastDeletion = null; lastDeleteKey = -1; }
            if (!edit.replacement().isEmpty()) {
                boolean ordinary = key != null && edit.removed.isEmpty();
                boolean displaced = edit.offset != continuation;
                int insertion = ordinary && !displaced ? activeInsertion : 0;
                if (!ordinary || displaced) {
                    if (revision == null) revision = newRevision(edit.offset, i, "", edit.replacement(), placed, false);
                    else revision.inserted = edit.replacement();
                    insertion = revision.id;
                    if (displaced) nodes.add(boundary(Math.min(continuation, liveLength())), new Break(insertion));
                } else if (insertion > 0) {
                    revision = revisions.get(insertion - 1); revision.inserted += edit.replacement(); revision.lastEvent = i; revision.end = placed;
                }
                boolean moved = paste && clipboard != null && plain(clipboard).equals(edit.replacement());
                if (moved && revision != null) {
                    revision.movedFrom = clipboardRevision;
                    notes.add("Revision " + revision.id + ": exact Ctrl+V payload linked to Ctrl+X revision " + clipboardRevision
                            + "; inferred clipboard lineage, original character timing retained. Placement timing is separate.");
                }
                List<Node> added = new ArrayList<>();
                for (int j = 0; j < edit.replacement().length(); j++) {
                    char ch = edit.replacement().charAt(j); long identity = nextIdentity++;
                    if (moved) {
                        Atom old = clipboard.get(j);
                        added.add(new Atom(ch, identity, old.originIdentity, old.keyIndex, old.start, old.end,
                                insertion, clipboardRevision, placed, "cut/paste (inferred)"));
                    } else {
                        Long started = key == null ? null : millis(key) - origin;
                        Long ended = keyIndex != null && releases.containsKey(keyIndex) ? millis(log.events.get(releases.get(keyIndex))) - origin : null;
                        added.add(new Atom(ch, identity, identity, keyIndex, started, ended, insertion, 0, placed, ordinary ? "typed" : "bulk edit"));
                    }
                }
                nodes.addAll(boundary(edit.offset), added);
                continuation = edit.offset + edit.replacement().length(); activeInsertion = ordinary ? insertion : 0;
                lastDeletion = null; lastDeleteKey = -1;
            }
            command = null;
        }
        live.addAll(atoms(nodes)); text = plain(live);
        if (!text.equals(log.finalText)) throw new IllegalArgumentException("Revision projection disagrees with final replay text");
        notes.add("Revisions are derived only from internal edits. Consecutive backspaces are grouped; normal continuous typing is unmarked. Displaced typing and bulk edits use insertion braces. Replacement deletion/insertion shares a revision number. Numbering may differ from Inputlog.");
        notes.add("S-Notation is rendered from structured characters, deletion tombstones and break markers. Deleted content cannot leak into reconstructed text. Literal notation delimiters are escaped; whitespace displays as ·.");
    }
    private Revision newRevision(int offset, int event, String removed, String inserted, long time, boolean cut) {
        Revision revision = new Revision(revisions.size() + 1, offset, event, removed, inserted, time, cut);
        revisions.add(revision); return revision;
    }
    private long millis(LogEvent event) { return Duration.between(log.events.get(0).time, event.time).toMillis(); }
    private static boolean near(KeyLogEvent key, EditEvent edit) {
        long delta = Duration.between(key.time, edit.time).toMillis(); return delta >= 0 && delta <= 250;
    }
    private static boolean control(KeyLogEvent key, int code) {
        return key.keyCode == code && key.modifiers != null && (key.modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
    }
    private int liveLength() { int length = 0; for (Node node : nodes) if (node instanceof Atom) length++; return length; }
    // Right affinity: insertion at a deletion boundary follows its tombstone.
    private int boundary(int offset) {
        int position = 0;
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i) instanceof Atom) {
            if (position == offset) return i; position++;
        }
        if (position != offset) throw new IllegalArgumentException("Revision offset outside live document: " + offset);
        return nodes.size();
    }
    static List<Atom> atoms(List<Node> nodes) {
        List<Atom> result = new ArrayList<>(); for (Node node : nodes) if (node instanceof Atom) result.add((Atom) node); return result;
    }
    static String plain(List<Atom> atoms) { StringBuilder text = new StringBuilder(); for (Atom atom : atoms) text.append(atom.value); return text.toString(); }
    String notation() { return render(nodes, false); }
    List<Node> fragment(int start, int end) {
        List<Node> result = new ArrayList<>(); int position = 0;
        for (Node node : nodes) {
            if (node instanceof Atom) {
                if (position >= start && position < end) result.add(node);
                position++;
            } else if (position >= start && position <= end) result.add(node);
        }
        return result;
    }
    static String notation(List<Node> nodes) { return render(nodes, false); }
    String context(boolean onlyInsertions) { return render(nodes, onlyInsertions); }
    private static String render(List<Node> nodes, boolean hideDeletions) {
        StringBuilder out = new StringBuilder(); int insertion = 0;
        for (Node node : nodes) {
            int next = node instanceof Atom ? ((Atom) node).insertedRevision : 0;
            if (next != insertion) {
                if (insertion != 0) out.append('}').append(insertion);
                if (next != 0) out.append('{'); insertion = next;
            }
            if (node instanceof Atom) out.append(visible(((Atom) node).value));
            else if (!hideDeletions && node instanceof Deletion) {
                Deletion deletion = (Deletion) node;
                out.append('[').append(render(deletion.children, false)).append(']').append(deletion.revision.id).append('|').append(deletion.revision.id);
            } else if (!hideDeletions && node instanceof Break) out.append('|').append(((Break) node).revision);
        }
        if (insertion != 0) out.append('}').append(insertion); return out.toString();
    }
    private static String visible(char ch) {
        if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) return "·";
        return "[]{}|\\·".indexOf(ch) >= 0 ? "\\" + ch : "" + ch;
    }
}
