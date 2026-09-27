package org.cerebellum.morphometry;

import ij.IJ;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Collects the notes and warnings produced during one run of the plugin.
 *
 * <p>Every message is forwarded to a sink as it arrives (the FIJI Log window by default, so users
 * see progress live) and is also kept, so that the plugin can summarise warnings at the end of
 * the run and record them in the exported workbook. Keeping them here rather than calling
 * {@code IJ.log} directly from the geometry code also lets tests assert on them.</p>
 *
 * <p>{@link #withPrefix} returns a view that prefixes every message (e.g. {@code "[Instance 2] "})
 * while recording into the same underlying list.</p>
 */
public final class Diagnostics {

    /** Prefix on every line written to the sink, so plugin output stands out in the Log window. */
    public static final String LOG_PREFIX = "[Cerebellar Morphometry] ";

    public enum Level { NOTE, WARNING }

    /** One recorded message. */
    public static final class Entry {
        private final Level level;
        private final String message;

        Entry(Level level, String message) {
            this.level = level;
            this.message = message;
        }

        public Level getLevel() {
            return level;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return (level == Level.WARNING ? "Warning: " : "Note: ") + message;
        }
    }

    private final List<Entry> entries;
    private final Consumer<String> sink;
    private final String prefix;

    /** Records messages and echoes them to the FIJI Log window. */
    public Diagnostics() {
        this(IJ::log);
    }

    /** Records messages and echoes each formatted line to {@code sink}. */
    public Diagnostics(Consumer<String> sink) {
        this(new ArrayList<>(), sink, "");
    }

    private Diagnostics(List<Entry> entries, Consumer<String> sink, String prefix) {
        this.entries = entries;
        this.sink = sink;
        this.prefix = prefix;
    }

    /** Records messages without echoing them anywhere. */
    public static Diagnostics silent() {
        return new Diagnostics(line -> { });
    }

    /** A view that prepends {@code extraPrefix} to every message and records into this same list. */
    public Diagnostics withPrefix(String extraPrefix) {
        return new Diagnostics(entries, sink, prefix + extraPrefix);
    }

    /** Informational message: something the user may want to know, but nothing is wrong. */
    public void note(String message) {
        add(Level.NOTE, message);
    }

    /** Something that probably affects the results and deserves the user's attention. */
    public void warn(String message) {
        add(Level.WARNING, message);
    }

    private void add(Level level, String message) {
        Entry entry = new Entry(level, prefix + message);
        synchronized (entries) {
            entries.add(entry);
        }
        sink.accept(LOG_PREFIX + entry);
    }

    public List<Entry> getEntries() {
        synchronized (entries) {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }

    public List<String> getWarnings() {
        List<String> out = new ArrayList<>();
        for (Entry e : getEntries()) {
            if (e.level == Level.WARNING) {
                out.add(e.message);
            }
        }
        return out;
    }

    public boolean hasWarnings() {
        return !getWarnings().isEmpty();
    }
}
