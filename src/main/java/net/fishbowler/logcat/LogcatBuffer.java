package net.fishbowler.logcat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Captures lines from {@code adb logcat} into a fixed-capacity circular buffer.
 *
 * <p>Thread-safe; all mutable state is guarded by a {@link ReentrantLock}.
 */
public class LogcatBuffer {

    private static final Logger LOG = Logger.getLogger(LogcatBuffer.class.getName());

    private final int capacity;
    private final Deque<String> buffer;
    private final ReentrantLock lock = new ReentrantLock();
    private Thread readerThread;
    private Process adbProcess;

    /**
     * Creates a buffer with the given maximum line capacity.
     *
     * @param capacity maximum number of lines retained; oldest lines are evicted when full
     */
    public LogcatBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    /**
     * Starts the {@code adb logcat} subprocess and begins capturing output.
     *
     * <p>The tag filter is read from the {@code LOGCAT_TAGS} environment variable;
     * it defaults to {@code *:D *:S}.
     *
     * @throws RuntimeException if {@code adb} is not found on PATH
     */
    public void start() {
        verifyAdbOnPath();

        String tags = System.getenv("LOGCAT_TAGS");
        if (tags == null || tags.isBlank()) {
            tags = "*:D *:S";
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("adb");
        cmd.add("logcat");
        cmd.addAll(List.of(tags.split("\\s+")));

        LOG.info("Starting adb logcat with filter: " + tags);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        try {
            adbProcess = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start adb logcat: " + e.getMessage(), e);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(adbProcess.getInputStream()));

        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLine(line);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    LOG.log(Level.WARNING, "adb logcat reader stopped unexpectedly", e);
                }
            }
        }, "logcat-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Stops the {@code adb logcat} subprocess and the reader thread.
     */
    public void stop() {
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (adbProcess != null) {
            adbProcess.destroy();
        }
    }

    /**
     * Discards all buffered lines.
     *
     * <p>Call this at the start of each test session to ensure previous output does not
     * pollute assertions in the current session.
     */
    public void clear() {
        lock.lock();
        try {
            buffer.clear();
            LOG.fine("Logcat buffer cleared");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns all currently buffered lines matching the given Java regex.
     *
     * <p>Performs a substring match ({@link java.util.regex.Matcher#find()}); the pattern
     * does not need to match the entire line. Lines are returned in the order received.
     *
     * @param regex Java regular expression
     * @return matching lines; empty list if none match
     * @throws PatternSyntaxException if {@code regex} is not a valid Java regex
     */
    public List<String> findMatchingLines(String regex) {
        Pattern pattern = Pattern.compile(regex);
        lock.lock();
        try {
            List<String> results = new ArrayList<>();
            for (String line : buffer) {
                if (pattern.matcher(line).find()) {
                    results.add(line);
                }
            }
            return results;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------

    private void appendLine(String line) {
        lock.lock();
        try {
            if (buffer.size() >= capacity) {
                buffer.pollFirst();
            }
            buffer.addLast(line);
        } finally {
            lock.unlock();
        }
    }

    private static void verifyAdbOnPath() {
        try {
            Process p = new ProcessBuilder("adb", "version").start();
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(
                    "adb not found on PATH. Install Android SDK platform-tools and add them to PATH.", e);
        }
    }
}
