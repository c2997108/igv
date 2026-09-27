package org.broad.igv.blast;

import org.broad.igv.prefs.PreferencesManager;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LocalBlastClientTest {
    @Test public void preservesLocalizedNativeDiagnosticsAndUtf8Output() throws IOException {
        Path log = Files.createTempFile("blast-diagnostic", ".log");
        String previous = System.getProperty("native.encoding");
        try {
            System.setProperty("native.encoding", "windows-31j");
            String message = "BLAST error: ファイル名が長すぎます";
            Files.writeString(log, message, Charset.forName("windows-31j"));
            assertEquals(message, LocalBlastClient.diagnostic(log));
            Files.writeString(log, message, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(message, LocalBlastClient.diagnostic(log));
        } finally {
            if (previous == null) System.clearProperty("native.encoding");
            else System.setProperty("native.encoding", previous);
            Files.deleteIfExists(log);
        }
    }

    @Test public void normalizesSingleFastaAndRna() {
        assertEquals("ACGTNNACGT", LocalBlastClient.normalizeSequence(">query\nacgu nn\nACGT\n"));
        assertThrows(IllegalArgumentException.class, () -> LocalBlastClient.normalizeSequence(">one\nACGTACGT\n>two\nACGTACGT"));
        assertThrows(IllegalArgumentException.class, () -> LocalBlastClient.normalizeSequence("ACGT<script>"));
        assertThrows(IllegalArgumentException.class, () -> LocalBlastClient.normalizeSequence("ACGT"));
    }

    @Test public void doesNotApplyBlatEightKilobaseLimit() {
        String sequence = "ACGT".repeat(2500);
        assertEquals(sequence, LocalBlastClient.normalizeSequence(sequence));
    }

    @Test public void requiresDatabaseAndPositiveSearchSettings() {
        assertEquals("", new LocalBlastClient.Settings("auto", "", "auto", 10, 100, 1, 60).database());
        assertThrows(IllegalArgumentException.class, () -> new LocalBlastClient.Settings("blastn", "db", "auto", Double.NaN, 100, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new LocalBlastClient.Settings("blastn", "db", "auto", 10, 0, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new LocalBlastClient.Settings("blastn", "db", "blastp", 10, 100, 1, 60));
        assertEquals("auto", LocalBlastClient.Settings.fromPreferences(PreferencesManager.getPreferences()).executable());
    }

    @Test public void preservesPathsAsArgumentsAndChoosesShortQueryTask() {
        var settings = new LocalBlastClient.Settings("C:\\Program Files\\BLAST\\blastn.exe", "D:\\My database\\reference", "auto", 1e-5, 20, 2, 60);
        var command = LocalBlastClient.command(settings, "ACGTACGT", Path.of("query with spaces.fa"), Path.of("results.tsv"));
        assertEquals(settings.executable(), command.getFirst());
        assertTrue(command.get(command.indexOf("-db") + 1).contains(settings.database()));
        assertEquals("query with spaces.fa", command.get(command.indexOf("-query") + 1));
        assertEquals("blastn-short", command.get(command.indexOf("-task") + 1));
        var longCommand = LocalBlastClient.command(settings, "ACGT".repeat(100), Path.of("q"), Path.of("r"));
        assertEquals("blastn", longCommand.get(longCommand.indexOf("-task") + 1));
    }

    @Test public void reportsMissingExecutable() {
        var settings = new LocalBlastClient.Settings("/nonexistent/igv-blastn", "database", "auto", 10, 100, 1, 60);
        IOException error = assertThrows(IOException.class, () -> LocalBlastClient.search(settings, "ACGTACGT", null));
        assertTrue(error.getMessage().contains("Cannot start BLAST+"));
    }

    @Test public void dustDefaultsToDisabledForAllSearchTasksAndCanBeEnabled() {
        assertFalse(LocalBlastClient.Settings.fromPreferences(PreferencesManager.getPreferences()).dustFiltering());
        for (String task : List.of("auto", "blastn", "blastn-short", "megablast", "dc-megablast")) {
            for (String sequence : List.of("A".repeat(25), "A".repeat(100))) {
                var unfiltered = new LocalBlastClient.Settings("blastn", "db", task, 10, 100, 1, 60);
                var command = LocalBlastClient.command(unfiltered, sequence, Path.of("q"), Path.of("r"));
                assertEquals("no", command.get(command.indexOf("-dust") + 1));
                var filtered = new LocalBlastClient.Settings("blastn", "db", task, 10, 100, 1, 60, true);
                command = LocalBlastClient.command(filtered, sequence, Path.of("q"), Path.of("r"));
                assertEquals("yes", command.get(command.indexOf("-dust") + 1));
            }
        }
    }

    @Test public void interruptedSearchDoesNotStartProcess() {
        var settings = new LocalBlastClient.Settings("/nonexistent/igv-blastn", "database", "auto", 10, 100, 1, 60);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> LocalBlastClient.search(settings, "ACGTACGT", null, ProcessBuilder::start));
        } finally {
            Thread.interrupted();
        }
    }

    private LocalBlastClient.ProcessStarter fixture(String mode, AtomicReference<Process> process,
                                                   AtomicReference<Path> output, CountDownLatch started) {
        return builder -> {
            List<String> command = builder.command();
            output.set(Path.of(command.get(command.indexOf("-out") + 1)));
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String classes;
            try {
                classes = Path.of(LocalBlastClientTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            } catch (Exception e) {
                throw new IOException(e);
            }
            builder.command(java, "-cp", classes, ProcessFixture.class.getName(), mode, output.get().toString());
            Process child = builder.start();
            process.set(child);
            started.countDown();
            return child;
        };
    }

    @Test public void timeoutTerminatesProcessAndRemovesTemporaryFiles() {
        var process = new AtomicReference<Process>();
        var output = new AtomicReference<Path>();
        var settings = new LocalBlastClient.Settings("fixture", "db", "auto", 10, 100, 1, 1);
        IOException error = assertThrows(IOException.class, () -> LocalBlastClient.search(settings, "ACGTACGT", null,
                fixture("wait", process, output, new CountDownLatch(1))));
        assertTrue(error.getMessage(), error.getMessage().contains("timeout"));
        assertFalse(process.get().isAlive());
        assertFalse(Files.exists(output.get().getParent()));
    }

    @Test public void cancellationTerminatesRunningProcessAndRemovesTemporaryFiles() throws Exception {
        var process = new AtomicReference<Process>();
        var output = new AtomicReference<Path>();
        var error = new AtomicReference<Throwable>();
        var started = new CountDownLatch(1);
        var settings = new LocalBlastClient.Settings("fixture", "db", "auto", 10, 100, 1, 30);
        Thread worker = new Thread(() -> {
            try {
                LocalBlastClient.search(settings, "ACGTACGT", null, fixture("wait", process, output, started));
            } catch (Throwable e) {
                error.set(e);
            }
        });
        worker.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(10_000);
            assertFalse(worker.isAlive());
            assertTrue(String.valueOf(error.get()), error.get() instanceof InterruptedException);
            assertFalse(process.get().isAlive());
            assertFalse(Files.exists(output.get().getParent()));
        } finally {
            worker.interrupt();
            if (process.get() != null && process.get().isAlive()) process.get().destroyForcibly();
        }
    }

    @Test public void largeDiagnosticDoesNotBlockProcessAndPreservesExitStatus() {
        var process = new AtomicReference<Process>();
        var output = new AtomicReference<Path>();
        var settings = new LocalBlastClient.Settings("fixture", "db", "auto", 10, 100, 1, 10);
        IOException error = assertThrows(IOException.class, () -> LocalBlastClient.search(settings, "ACGTACGT", null,
                fixture("error", process, output, new CountDownLatch(1))));
        assertTrue(error.getMessage(), error.getMessage().contains("exited with code 2"));
        assertTrue(error.getMessage().contains("fixture diagnostic"));
        assertFalse(Files.exists(output.get().getParent()));
    }

    /** A real child JVM isolates process lifecycle tests from the installed BLAST version. */
    public static class ProcessFixture {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), "");
            if (args[0].equals("error")) {
                System.err.print("fixture diagnostic\n" + "x".repeat(256_000));
                System.exit(2);
            }
            Thread.sleep(300_000);
        }
    }
}
