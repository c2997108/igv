package org.broad.igv.blast;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.prefs.IGVPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.broad.igv.prefs.Constants.*;

/** Runs nucleotide searches against a local BLAST+ database without invoking a shell. */
public class LocalBlastClient {
    public static final int MINIMUM_SEQUENCE_LENGTH = 7;
    public static final int MAXIMUM_SEQUENCE_LENGTH = 1_000_000;
    private static final String OUTPUT_FORMAT = "6 qseqid sseqid pident length mismatch gapopen qstart qend sstart send evalue bitscore";

    public record Settings(String executable, String database, String task, double evalue,
                           int maxTargets, int threads, int timeoutSeconds, boolean dustFiltering) {
        public Settings(String executable, String database, String task, double evalue,
                        int maxTargets, int threads, int timeoutSeconds) {
            this(executable, database, task, evalue, maxTargets, threads, timeoutSeconds, false);
        }

        public Settings {
            executable = executable == null || executable.isBlank() ? "auto" : executable.strip();
            database = database == null ? "" : database.strip();
            if (task == null || !Set.of("auto", "blastn", "blastn-short", "megablast", "dc-megablast").contains(task)) {
                throw new IllegalArgumentException("Unsupported BLAST task: " + task);
            }
            if (!Double.isFinite(evalue) || evalue <= 0 || maxTargets <= 0 || threads <= 0 || timeoutSeconds <= 0) {
                throw new IllegalArgumentException("BLAST E-value, maximum targets, threads and timeout must be positive.");
            }
        }

        public static Settings fromPreferences(IGVPreferences prefs) {
            try {
                return new Settings(prefs.get(BLAST_EXECUTABLE), prefs.get(BLAST_DATABASE), prefs.get(BLAST_TASK),
                        Double.parseDouble(prefs.get(BLAST_EVALUE)), Integer.parseInt(prefs.get(BLAST_MAX_TARGETS)),
                        Integer.parseInt(prefs.get(BLAST_THREADS)), Integer.parseInt(prefs.get(BLAST_TIMEOUT)),
                        prefs.getAsBoolean(BLAST_DUST));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid numeric BLAST setting in View > Preferences > Advanced.", e);
            }
        }
    }

    public static String normalizeSequence(String input) {
        if (input == null) throw new IllegalArgumentException("Enter a nucleotide sequence.");
        StringBuilder sequence = new StringBuilder();
        boolean headerSeen = false;
        for (String line : input.strip().split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(">")) {
                if (headerSeen || !sequence.isEmpty()) throw new IllegalArgumentException("Enter a single FASTA sequence.");
                headerSeen = true;
            } else {
                sequence.append(trimmed.replaceAll("\\s", ""));
            }
        }
        String result = sequence.toString().toUpperCase(Locale.ROOT).replace('U', 'T');
        if (!result.matches("[ACGTRYSWKMBDHVN]+")) throw new IllegalArgumentException("BLAST requires a nucleotide sequence (IUPAC DNA/RNA bases).");
        if (result.length() < MINIMUM_SEQUENCE_LENGTH || result.length() > MAXIMUM_SEQUENCE_LENGTH) {
            throw new IllegalArgumentException("BLAST sequences must be between 7 and 1,000,000 bases in length.");
        }
        return result;
    }

    static List<String> command(Settings settings, String sequence, Path query, Path output) {
        String task = settings.task().equals("auto") ? (sequence.length() < 50 ? "blastn-short" : "blastn") : settings.task();
        return List.of(settings.executable().strip(), "-query", query.toString(), "-db", databaseArgument(settings.database().strip()),
                "-task", task, "-dust", settings.dustFiltering() ? "yes" : "no", "-evalue", Double.toString(settings.evalue()),
                "-max_target_seqs", Integer.toString(settings.maxTargets()), "-num_threads", Integer.toString(settings.threads()),
                "-outfmt", OUTPUT_FORMAT, "-out", output.toString());
    }

    @SuppressWarnings("removal")
    static String databaseArgument(String database) {
        // BLAST splits -db into a list itself, even when ProcessBuilder passes a single argument.
        // https://www.ncbi.nlm.nih.gov/books/NBK569860/
        if (!database.chars().anyMatch(Character::isWhitespace)) return database;
        // Windows Java's default (legacy) argument encoder does not escape interior quotes.
        // The strict encoder and Unix do; padding avoids Java treating these as outer quotes.
        boolean legacyWindows = System.getProperty("os.name").startsWith("Windows") &&
                !"false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true")) &&
                System.getSecurityManager() == null;
        String quote = legacyWindows ? "\\\"" : "\"";
        return " " + quote + database + quote + " ";
    }

    public static List<BlastHit> search(Settings settings, String input, Genome genome) throws IOException, InterruptedException {
        String sequence = normalizeSequence(input);
        return search(prepare(settings, genome, message -> { }), sequence, genome, ProcessBuilder::start);
    }

    public static Settings prepare(Settings settings, Genome genome, Consumer<String> progress) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Path executable = BlastTools.resolve("blastn", settings.executable());
        Path database = BlastDatabase.prepare(genome, settings.database(), settings.executable(), executable, settings.timeoutSeconds(), progress);
        return new Settings(executable.toString(), database.toString(), settings.task(), settings.evalue(),
                settings.maxTargets(), settings.threads(), settings.timeoutSeconds(), settings.dustFiltering());
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    static List<BlastHit> search(Settings settings, String input, Genome genome, ProcessStarter starter) throws IOException, InterruptedException {
        String sequence = normalizeSequence(input);
        Path directory = Files.createTempDirectory("igv-blast-");
        Path query = directory.resolve("query.fa");
        Path output = directory.resolve("hits.tsv");
        Path errors = directory.resolve("stderr.txt");
        Throwable failure = null;
        try {
            Files.writeString(query, ">igv_query\n" + sequence + "\n", StandardCharsets.US_ASCII);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            ProcessBuilder builder = new ProcessBuilder(command(settings, sequence, query, output));
            builder.redirectError(errors.toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            int exitCode = runProcess(builder, settings.timeoutSeconds(), "blastn", starter);
            if (exitCode != 0) {
                throw new IOException("BLAST+ exited with code " + exitCode + ". " + diagnostic(errors));
            }
            if (!Files.exists(output)) throw new IOException("BLAST+ did not create a result file.");
            try (var reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
                return BlastHit.parse(reader, genome, BlastDatabase.subjectIds(Path.of(settings.database())));
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                removeTemporaryFiles(query, output, errors, directory);
            } catch (IOException e) {
                // A cleanup error must not replace the BLAST diagnostic, timeout or cancellation.
                if (failure != null) failure.addSuppressed(e);
                else throw e;
            }
        }
    }

    static int runProcess(ProcessBuilder builder, int timeout, String tool, ProcessStarter starter) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Process process;
        try {
            process = starter.start(builder);
        } catch (IOException e) {
            throw new IOException("Cannot start BLAST+ " + tool + ". Check its executable path. " + e.getMessage(), e);
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException("BLAST+ " + tool + " exceeded the configured timeout (" + timeout + " seconds).");
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    static String diagnostic(Path file) throws IOException {
        try {
            return readDiagnostic(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        } catch (CharacterCodingException e) {
            // Windows can write localized system errors in its native code page.
            // Preserve the tool's diagnostic instead of replacing it with a decoding error.
            Charset nativeEncoding = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
            return readDiagnostic(new BufferedReader(new InputStreamReader(Files.newInputStream(file), nativeEncoding)));
        }
    }

    private static String readDiagnostic(Reader reader) throws IOException {
        try (reader) {
            char[] buffer = new char[4096];
            int count = reader.read(buffer);
            return count < 0 ? "" : new String(buffer, 0, count).strip();
        }
    }

    static void removeTemporaryFiles(Path... paths) throws IOException {
        // Windows can briefly retain redirected file handles after a process reports termination.
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                IOException last = null;
                for (Path path : paths) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        last = e;
                    }
                }
                if (last == null) return;
                if (attempt == 9) throw last;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
