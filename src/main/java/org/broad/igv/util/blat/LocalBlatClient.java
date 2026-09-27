package org.broad.igv.util.blat;

import org.broad.igv.blast.LocalBlastClient;
import org.broad.igv.feature.PSLRecord;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.tribble.PSLCodec;
import org.broad.igv.prefs.Constants;
import org.broad.igv.prefs.PreferencesManager;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Direct local BLAT invocation: no shell, Python, or external BLAT server required. */
public class LocalBlatClient {
    public static List<PSLRecord> search(String db, String sequence, Genome genome) throws IOException, InterruptedException {
        var preferences = PreferencesManager.getPreferences();
        Path executable = BlatTools.resolve(preferences.get(Constants.BLAT_EXECUTABLE));
        return search(executable, db, sequence, genome, preferences.getAsInt(Constants.BLAT_TIMEOUT));
    }

    static List<PSLRecord> search(Path executable, String db, String sequence, Genome genome, int timeout)
            throws IOException, InterruptedException {
        sequence = LocalBlastClient.normalizeSequence(sequence);
        if (sequence.length() < BlatClient.MINIMUM_BLAT_LENGTH || sequence.length() > 8000) {
            throw new IllegalArgumentException("BLAT searches require 20 to 8,000 bases.");
        }
        if (timeout < 1) throw new IllegalArgumentException("BLAT timeout must be positive.");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Path directory = Files.createTempDirectory("igv-blat-");
        Path query = directory.resolve("query.fa");
        Path output = directory.resolve("output.psl");
        Path errors = directory.resolve("blat.log");
        Path exported = directory.resolve("reference.fa");
        Throwable failure = null;
        try {
            Path reference = localFasta(db);
            if (reference == null && genome != null) reference = localFasta(genome.getConfig().fastaURL);
            if (reference == null) {
                if (genome == null) throw new IOException("Load a reference genome or specify a local FASTA file for BLAT.");
                exportReference(genome, exported);
                reference = exported;
            }
            Files.writeString(query, ">igv_query\n" + sequence + "\n", StandardCharsets.US_ASCII);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), reference.toString(), query.toString(),
                    output.toString(), "-noHead", "-t=dna", "-q=dna", "-out=psl");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors.toFile());
            Process process = builder.start();
            try {
                process.getOutputStream().close();
                if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException("BLAT exceeded the configured timeout (" + timeout + " seconds).");
                if (process.exitValue() != 0) {
                    String diagnostic;
                    try (var input = Files.newInputStream(errors)) {
                        diagnostic = new String(input.readNBytes(65_536), Charset.forName(System.getProperty("native.encoding", "UTF-8")));
                    }
                    throw new IOException("BLAT exited with code " + process.exitValue() + ". " + diagnostic.strip());
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    boolean interrupted = Thread.interrupted();
                    try { process.waitFor(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                    finally { if (interrupted) Thread.currentThread().interrupt(); }
                }
            }
            List<PSLRecord> hits = new ArrayList<>();
            PSLCodec codec = new PSLCodec(genome, true);
            try (var reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    PSLRecord hit = codec.decode(line);
                    if (hit == null && !line.isBlank()) throw new IOException("BLAT returned an invalid PSL record.");
                    if (hit != null) hits.add(hit);
                }
            }
            return hits;
        } catch (IOException | InterruptedException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                for (Path file : List.of(query, output, errors, exported, directory)) Files.deleteIfExists(file);
            } catch (IOException e) {
                if (failure != null) failure.addSuppressed(e);
                else throw e;
            }
        }
    }

    private static Path localFasta(String source) {
        if (source == null || source.isBlank() || source.toLowerCase(Locale.ROOT).endsWith(".gz")) return null;
        try {
            Path path = source.startsWith("file:") ? Path.of(URI.create(source)) : Path.of(source);
            return Files.isRegularFile(path) ? path.toAbsolutePath().normalize() : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private static void exportReference(Genome genome, Path file) throws IOException, InterruptedException {
        try (var output = new BufferedOutputStream(Files.newOutputStream(file))) {
            for (String chromosome : genome.getChromosomeNames()) {
                output.write((">" + chromosome + "\n").getBytes(StandardCharsets.UTF_8));
                int length = genome.getChromosome(chromosome).getLength();
                for (int start = 0; start < length; ) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    int end = (int) Math.min(length, (long) start + 1_048_576);
                    byte[] bases = genome.getSequence(chromosome, start, end, false);
                    if (bases == null || bases.length != end - start) throw new IOException("Reference sequence is unavailable for " + chromosome + ".");
                    output.write(bases);
                    output.write('\n');
                    start = end;
                }
            }
        }
    }
}
