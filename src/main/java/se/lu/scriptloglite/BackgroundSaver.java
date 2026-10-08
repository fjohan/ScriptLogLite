package se.lu.scriptloglite;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Path;
import javax.swing.SwingUtilities;

/** Serializes immutable snapshots off the EDT and coalesces pending autosaves. */
final class BackgroundSaver implements AutoCloseable {
    final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "scriptloglite-save"); thread.setDaemon(true); return thread;
    });
    final Map<RecordingSession, SaveSnapshot> pending = new LinkedHashMap<>();
    boolean draining, closing;
    final java.util.function.Consumer<Exception> onError;
    BackgroundSaver(java.util.function.Consumer<Exception> onError) { this.onError = onError; }

    synchronized void automatic(RecordingSession filter) {
        if (closing) throw new IllegalStateException("Save worker is closed");
        synchronized (filter) {
            if (!filter.dirty || filter.eventCount() == 0) return;
            pending.put(filter, filter.snapshot());
        }
        if (!draining) {
            draining = true;
            writer.execute(this::drain);
        }
    }
    private void drain() {
        while (true) {
            Map<RecordingSession, SaveSnapshot> batch;
            synchronized (this) {
                batch = new LinkedHashMap<>(pending);
                pending.clear();
            }
            for (Map.Entry<RecordingSession, SaveSnapshot> entry : batch.entrySet()) {
                RecordingSession filter = entry.getKey();
                SaveSnapshot snapshot = entry.getValue();
                try {
                    synchronized (filter) { if (snapshot.revision <= filter.automaticRevision) continue; }
                    snapshot.save(filter.automaticPath);
                    filter.savedAutomatically(snapshot.revision);
                } catch (Exception exception) { report(exception); }
            }
            synchronized (this) {
                if (pending.isEmpty()) { draining = false; return; }
                if (!closing) {
                    // Yield to explicit Save requests already waiting in the executor.
                    writer.execute(this::drain);
                    return;
                }
            }
            // At shutdown, drain the remainder without submitting new executor tasks.
        }
    }
    void named(SaveSnapshot snapshot, Path path, java.util.function.Consumer<Exception> completion) {
        writer.execute(() -> {
            Exception failure = null;
            try { snapshot.save(path); } catch (Exception exception) { failure = exception; }
            final Exception result = failure;
            SwingUtilities.invokeLater(() -> completion.accept(result));
        });
    }
    void report(Exception exception) {
        System.err.println("Log save failed: " + exception.getMessage());
        SwingUtilities.invokeLater(() -> onError.accept(exception));
    }
    java.util.concurrent.Future<?> flush() {
        java.util.concurrent.CompletableFuture<Void> complete = new java.util.concurrent.CompletableFuture<>();
        writer.execute(new Runnable() {
            public void run() {
                synchronized (BackgroundSaver.this) {
                    if (!draining && pending.isEmpty()) complete.complete(null);
                    else writer.execute(this);
                }
            }
        });
        return complete;
    }
    public void close() {
        synchronized (this) { closing = true; writer.shutdown(); }
        try {
            while (!writer.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) { }
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
