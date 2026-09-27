package org.broad.igv.blast;

import org.broad.igv.DirectoryManager;
import org.broad.igv.feature.genome.Genome;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Reuses nucleotide databases or builds a cached database from the current reference. */
public class BlastDatabase {
    public static boolean exists(Path prefix) throws IOException {
        if (nonempty(Path.of(prefix + ".nal"))) return true;
        for (String volume : List.of("", ".00")) {
            if (nonempty(Path.of(prefix + volume + ".nhr")) && nonempty(Path.of(prefix + volume + ".nin")) &&
                    nonempty(Path.of(prefix + volume + ".nsq"))) return true;
        }
        return false;
    }

    private static boolean nonempty(Path file) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) > 0;
    }

    public static Path prepare(Genome genome, String configured, String executablePreference, Path blastn,
                               int timeout, Consumer<String> progress) throws IOException, InterruptedException {
        String cache = System.getProperty("igv.blast.cache.dir");
        Path root = cache == null ? DirectoryManager.getCacheDirectory().toPath().resolve("blast") : Path.of(cache);
        return prepare(genome, configured, executablePreference, blastn, timeout, progress, root);
    }

    static Path prepare(Genome genome, String configured, String executablePreference, Path blastn, int timeout,
                        Consumer<String> progress, Path cacheRoot) throws IOException, InterruptedException {
        Path reference = genome == null ? null : localFile(genome.getConfig().fastaURL);
        boolean cached = configured == null || configured.isBlank();
        Path prefix;
        if (!cached) {
            prefix = Path.of(configured).toAbsolutePath().normalize();
            if (exists(prefix) && !Files.exists(Path.of(prefix + ".igv-building"))) return prefix;
            // BLAST also looks for named databases in BLASTDB.
            String locations = System.getenv("BLASTDB");
            if (locations != null && !Path.of(configured).isAbsolute()) {
                for (String location : locations.split(java.io.File.pathSeparator)) {
                    if (location.isBlank()) continue;
                    Path candidate = Path.of(location).resolve(configured).toAbsolutePath().normalize();
                    if (exists(candidate)) return candidate;
                }
            }
        } else {
            if (genome == null) throw new IOException("Load a reference genome before creating a BLAST database.");
            if (reference != null) {
                // Also accept databases made alongside the FASTA before this feature was enabled.
                String filename = reference.toString();
                for (String candidate : List.of(filename, filename.replaceFirst("(?i)\\.gz$", "").replaceFirst("(?i)\\.(fa|fasta|fna)$", ""))) {
                    Path adjacent = Path.of(candidate);
                    if (exists(adjacent) && !Files.exists(Path.of(adjacent + ".igv-building")) &&
                            Files.getLastModifiedTime(indexFile(adjacent)).compareTo(Files.getLastModifiedTime(reference)) >= 0) return adjacent;
                }
            }
            prefix = cacheRoot.resolve(referenceKey(genome, reference)).resolve("reference").toAbsolutePath().normalize();
            if (Files.exists(Path.of(prefix + ".igv-ready")) && exists(prefix)) return prefix;
        }
        if (genome == null) throw new IOException("BLAST database was not found: " + prefix + ". Load a reference genome to create it automatically.");
        Path makeblastdb = BlastTools.resolve("makeblastdb", executablePreference);
        Files.createDirectories(prefix.getParent());
        Path lockPath = Path.of(prefix + ".igv-lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = waitForLock(channel, timeout, progress)) {
            if ((!cached || Files.exists(Path.of(prefix + ".igv-ready"))) && exists(prefix)) return prefix;
            progress.accept("Creating BLAST database from the current reference...");
            build(genome, reference, prefix, makeblastdb, blastn, timeout);
            return prefix;
        }
    }

    private static Path indexFile(Path prefix) {
        for (String extension : List.of(".nin", ".00.nin", ".nal")) {
            Path path = Path.of(prefix + extension);
            if (Files.isRegularFile(path)) return path;
        }
        return prefix;
    }

    private static FileLock waitForLock(FileChannel channel, int timeout, Consumer<String> progress) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout * 1_000_000_000L;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) { }
            progress.accept("Waiting for reference database creation...");
            if (System.nanoTime() >= deadline) throw new IOException("Timed out waiting for BLAST database creation.");
            Thread.sleep(100);
        }
    }

    private static void build(Genome genome, Path reference, Path prefix, Path makeblastdb, Path blastn, int timeout) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory(prefix.getParent(), ".igv-build-");
        Path marker = Path.of(prefix + ".igv-building");
        Throwable failure = null;
        try {
            Files.writeString(marker, "Building reference database\n");
            Path staged = directory.resolve("reference");
            Path fasta = directory.resolve("input.fa");
            // -parse_seqids treats '|' as NCBI syntax and rejects ENA/custom IDs. Short local
            // IDs also avoid its length limit and accession rewriting. Keep a reversible map.
            if (reference != null) normalizeReference(reference, fasta, identifierFile(staged));
            else exportReference(genome, fasta, identifierFile(staged));
            Path log = directory.resolve("makeblastdb.log");
            ProcessBuilder builder = new ProcessBuilder(makeblastdb.toString(), "-in", "-", "-dbtype", "nucl", "-parse_seqids", "-title", "IGV_reference", "-out", "reference")
                    .directory(directory.toFile()).redirectInput(fasta.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            int exit = LocalBlastClient.runProcess(builder, timeout, "makeblastdb", ProcessBuilder::start);
            String diagnostic = LocalBlastClient.diagnostic(log);
            // BLAST+ 2.17 can finish the indexes then fail while generating metadata when the
            // absolute output directory contains spaces. Validate the database with blastn below.
            boolean metadataPathError = exit == 2 && directory.toString().contains(" ") &&
                    diagnostic.contains("No alias or index file found") && exists(staged);
            if (exit != 0 && !metadataPathError) throw new IOException("BLAST+ makeblastdb exited with code " + exit + ". " + diagnostic);
            if (!exists(staged)) throw new IOException("makeblastdb did not create a complete nucleotide database. " + diagnostic);
            // Checking with the search executable also catches incompatible PATH/bundled versions.
            validate(blastn, staged, timeout);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            // Version-5 databases embed their basename in LMDB files. Preserve 'reference' rather
            // than renaming its files. Expose other requested prefixes through a nucleotide alias.
            boolean alias = !prefix.getFileName().toString().equals("reference");
            Path destination = alias ? Path.of(prefix + ".igv-data") : prefix.getParent();
            Files.createDirectories(destination);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().startsWith("reference.")).toList()) {
                    Files.move(file, destination.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (alias) {
                Files.copy(identifierFile(destination.resolve("reference")), identifierFile(prefix), StandardCopyOption.REPLACE_EXISTING);
                Path temporaryAlias = directory.resolve("database.nal");
                String databasePath = destination.resolve("reference").toString().replace('\\', '/');
                Files.writeString(temporaryAlias, "TITLE IGV reference\nDBLIST \"" + databasePath + "\"\n", StandardCharsets.UTF_8);
                Files.move(temporaryAlias, Path.of(prefix + ".nal"), StandardCopyOption.REPLACE_EXISTING);
            }
            validate(blastn, prefix, timeout);
            // Publish readiness only after all volumes have been moved and validated.
            Files.writeString(Path.of(prefix + ".igv-ready"), "BLAST database complete\n");
        } catch (IOException | InterruptedException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                List<Path> cleanup = new ArrayList<>();
                try (var files = Files.list(directory)) { cleanup.addAll(files.toList()); }
                cleanup.add(directory);
                cleanup.add(marker);
                LocalBlastClient.removeTemporaryFiles(cleanup.toArray(Path[]::new));
            } catch (IOException e) {
                if (failure != null) failure.addSuppressed(e);
                else throw e;
            }
        }
    }

    private static void validate(Path blastn, Path prefix, int timeout) throws IOException, InterruptedException {
        LocalBlastClient.search(new LocalBlastClient.Settings(blastn.toString(), prefix.toString(), "blastn-short", 10, 1, 1, timeout),
                "ACGTACGTACGT", null, ProcessBuilder::start);
    }

    private static Path identifierFile(Path prefix) {
        return Path.of(prefix + ".igv-ids.tsv");
    }

    static Map<String, String> subjectIds(Path prefix) throws IOException {
        Path file = identifierFile(prefix);
        if (!Files.exists(file)) return Map.of(); // User-supplied and older databases retain their own IDs.
        Map<String, String> ids = new HashMap<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 2 || fields[0].isEmpty() || fields[1].isEmpty() ||
                        ids.putIfAbsent(fields[0], fields[1]) != null) {
                    throw new IOException("Invalid BLAST reference identifier mapping: " + file);
                }
            }
        }
        return ids;
    }

    private static String localId(long ordinal) {
        return "igvref_" + ordinal;
    }

    private static void normalizeReference(Path reference, Path destination, Path identifiers) throws IOException, InterruptedException {
        try (InputStream raw = Files.newInputStream(reference);
             InputStream input = reference.toString().toLowerCase(Locale.ROOT).endsWith(".gz") ? new GZIPInputStream(raw, 65536) : raw;
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 65536);
             var output = Files.newBufferedWriter(destination, StandardCharsets.UTF_8);
             var ids = Files.newBufferedWriter(identifiers, StandardCharsets.UTF_8)) {
            String line;
            long ordinal = 0;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (line.startsWith(">")) {
                    int end = 1;
                    while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
                    String original = line.substring(1, end);
                    if (original.isEmpty()) throw new IOException("Reference FASTA contains an empty sequence ID.");
                    String id = localId(++ordinal);
                    ids.write(id + "\t" + original + "\n");
                    output.write(">lcl|" + id + "\n");
                } else {
                    output.write(line);
                    output.write('\n');
                }
            }
        }
    }

    private static void exportReference(Genome genome, Path destination, Path identifiers) throws IOException, InterruptedException {
        try (var output = new BufferedOutputStream(Files.newOutputStream(destination));
             var ids = Files.newBufferedWriter(identifiers, StandardCharsets.UTF_8)) {
            long ordinal = 0;
            for (String chromosome : genome.getChromosomeNames()) {
                int length = genome.getChromosome(chromosome).getLength();
                String id = localId(++ordinal);
                ids.write(id + "\t" + chromosome + "\n");
                output.write((">lcl|" + id + "\n").getBytes(StandardCharsets.US_ASCII));
                for (int start = 0; start < length; ) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    int end = (int) Math.min(length, (long) start + 1_048_576);
                    byte[] sequence = genome.getSequence(chromosome, start, end, false);
                    if (sequence == null || sequence.length != end - start) throw new IOException("Reference sequence is unavailable for " + chromosome + ".");
                    output.write(sequence);
                    output.write('\n');
                    start = end;
                }
            }
        }
    }

    private static Path localFile(String source) throws IOException {
        if (source == null || source.isBlank()) return null;
        if (source.startsWith("http://") || source.startsWith("https://")) return null;
        try {
            Path file = source.startsWith("file:") ? Path.of(URI.create(source)) : Path.of(source);
            return Files.isRegularFile(file) ? file.toRealPath() : null;
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot read reference path: " + source, e);
        }
    }

    private static String referenceKey(Genome genome, Path reference) throws IOException {
        Path source = reference == null ? localFile(genome.getConfig().twoBitURL) : reference;
        String identity;
        if (source != null) {
            identity = source + "\n" + Files.size(source) + "\n" + Files.getLastModifiedTime(source);
        } else {
            identity = genome.getId() + "\n" + genome.getConfig().fastaURL + "\n" + genome.getConfig().twoBitURL + "\n" +
                    genome.getChromosomeNames() + "\n" + System.identityHashCode(genome);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
