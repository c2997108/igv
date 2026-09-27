package org.broad.igv.blast;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.InMemorySequence;
import org.broad.igv.feature.genome.load.GenomeConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class BlastDatabaseIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path blastn;
    private Path reference;
    private Path cache;
    private Genome genome;
    private String query;

    @Before public void setup() throws Exception {
        String bin = System.getProperty("igv.blast.bin");
        assumeTrue("Set igv.blast.bin to run real BLAST+ integration tests", bin != null);
        blastn = Path.of(bin, System.getProperty("os.name").startsWith("Windows") ? "blastn.exe" : "blastn");
        reference = temporary.newFile("reference with spaces.fa").toPath();
        cache = temporary.newFolder("cache with spaces").toPath();
        Random random = new Random(721);
        StringBuilder bases = new StringBuilder();
        for (int i = 0; i < 500; i++) bases.append("ACGT".charAt(random.nextInt(4)));
        query = bases.substring(100, 220);
        Files.writeString(reference, ">chrCustom\n" + bases + "\n");
        GenomeConfig config = new GenomeConfig();
        config.id = "automatic-blast-test";
        config.setName(config.id);
        config.fastaURL = reference.toString();
        config.setSequence(new InMemorySequence("chrCustom", bases.toString().getBytes(StandardCharsets.US_ASCII)));
        genome = new Genome(config);
    }

    private Path prepare(String prefix) throws Exception {
        return BlastDatabase.prepare(genome, prefix, blastn.toString(), blastn, 30, message -> { }, cache);
    }

    @Test public void automaticallyBuildsAndSearchesReferenceWithSpaces() throws Exception {
        Path prefix = prepare("");
        assertTrue(BlastDatabase.exists(prefix));
        var hits = LocalBlastClient.search(new LocalBlastClient.Settings(blastn.toString(), prefix.toString(), "auto", 10, 100, 1, 30), query, genome);
        assertFalse(hits.isEmpty());
        assertEquals("chrCustom", hits.getFirst().getChr());
        assertEquals(100, hits.getFirst().getStart());
        assertEquals(220, hits.getFirst().getEnd());
        assertTrue(Files.exists(Path.of(prefix + ".igv-ready")));
        assertFalse(Files.exists(Path.of(prefix + ".igv-building")));
    }

    @Test public void reusesDatabaseWithoutRequiringMakeblastdbAgain() throws Exception {
        Path prefix = prepare("");
        FileTime original = Files.getLastModifiedTime(Path.of(prefix + ".nin"));
        var progress = new ArrayList<String>();
        Path reused = BlastDatabase.prepare(genome, "", "/missing/blastn", blastn, 30, progress::add, cache);
        assertEquals(prefix, reused);
        assertEquals(original, Files.getLastModifiedTime(Path.of(prefix + ".nin")));
        assertTrue(progress.isEmpty());
    }

    @Test public void changedReferenceCreatesFreshDatabase() throws Exception {
        Path before = prepare("");
        Files.setLastModifiedTime(reference, FileTime.fromMillis(Files.getLastModifiedTime(reference).toMillis() + 2000));
        Path after = prepare("");
        assertNotEquals(before, after);
        assertTrue(BlastDatabase.exists(after));
        assertTrue(BlastDatabase.exists(before));
    }

    @Test public void missingExplicitPrefixIsCreatedFromReference() throws Exception {
        Path desired = cache.resolve("custom database");
        Path prefix = prepare(desired.toString());
        assertEquals(desired, prefix);
        assertTrue(BlastDatabase.exists(prefix));
        assertFalse(LocalBlastClient.search(new LocalBlastClient.Settings(blastn.toString(), prefix.toString(), "auto", 10, 100, 1, 30), query, genome).isEmpty());
    }

    @Test public void incompleteDatabaseIsRebuilt() throws Exception {
        Path desired = cache.resolve("incomplete");
        Files.writeString(Path.of(desired + ".nin"), "incomplete index");
        assertFalse(BlastDatabase.exists(desired));
        assertEquals(desired, prepare(desired.toString()));
        assertTrue(BlastDatabase.exists(desired));
    }

    @Test public void reusesExistingDatabaseAlongsideFasta() throws Exception {
        Path adjacent = prepare(reference.toString());
        assertEquals(adjacent, prepare(""));
    }

    @Test public void compressedFastaIsDecompressedForDatabaseCreation() throws Exception {
        Path compressed = temporary.newFile("reference.fa.gz").toPath();
        try (var output = new GZIPOutputStream(Files.newOutputStream(compressed))) {
            Files.copy(reference, output);
        }
        genome.getConfig().fastaURL = compressed.toString();
        Path prefix = prepare("");
        assertTrue(BlastDatabase.exists(prefix));
        assertFalse(LocalBlastClient.search(new LocalBlastClient.Settings(blastn.toString(), prefix.toString(), "auto", 10, 100, 1, 30), query, genome).isEmpty());
    }

    @Test public void referencesWithoutFastaAreExportedFromLoadedGenome() throws Exception {
        genome.getConfig().fastaURL = null;
        Path prefix = prepare("");
        assertTrue(BlastDatabase.exists(prefix));
        assertEquals(100, LocalBlastClient.search(new LocalBlastClient.Settings(blastn.toString(), prefix.toString(), "auto", 10, 100, 1, 30), query, genome).getFirst().getStart());
    }

    private void renameReference(String id) throws Exception {
        String bases = Files.readString(reference).split("\\R", 2)[1].strip();
        Files.writeString(reference, ">" + id + " description with spaces\n" + bases + "\n");
        genome.getConfig().setSequence(new InMemorySequence(id, bases.getBytes(StandardCharsets.US_ASCII)));
        genome = new Genome(genome.getConfig());
    }

    private void assertOriginalIdAfterSearch(String id) throws Exception {
        Path prefix = prepare("");
        var settings = new LocalBlastClient.Settings(blastn.toString(), prefix.toString(), "auto", 10, 100, 1, 30);
        BlastHit hit = LocalBlastClient.search(settings, query, genome).getFirst();
        assertEquals(id, hit.getSubjectId());
        assertEquals(id, hit.getChr());
        assertEquals(id, hit.resolveChromosome(genome));
        assertEquals(100, hit.getStart());
        assertEquals(220, hit.getEnd());
        // Export and session snapshots must remain usable without the internal ID mapping.
        assertEquals(id, hit.getText().split("\t")[1]);
        assertEquals(id, BlastHit.parse(new java.io.StringReader(hit.getText()), genome).getFirst().getSubjectId());
        assertEquals(prefix, prepare(""));
        assertEquals(id, LocalBlastClient.search(settings, query, null).getFirst().getSubjectId());
    }

    @Test public void enaIdentifierIsAcceptedAndRetainedInResults() throws Exception {
        String id = "ENA|HM106493|HM106493.1";
        renameReference(id);
        String original = Files.readString(reference);
        assertOriginalIdAfterSearch(id);
        assertEquals(original, Files.readString(reference));
    }

    @Test public void explicitEnaDatabaseAliasRestoresOriginalIdentifier() throws Exception {
        String id = "ENA|HM106493|HM106493.1";
        renameReference(id);
        Path prefix = prepare(cache.resolve("ENA custom database").toString());
        BlastHit hit = LocalBlastClient.search(new LocalBlastClient.Settings(blastn.toString(), prefix.toString(), "auto", 10, 100, 1, 30), query, genome).getFirst();
        assertEquals(id, hit.getSubjectId());
        assertEquals(id, hit.resolveChromosome(genome));
    }

    @Test public void compressedEnaReferenceRetainsIdentifier() throws Exception {
        String id = "ENA|HM106493|HM106493.1";
        renameReference(id);
        Path compressed = temporary.newFile("ENA.fa.gz").toPath();
        try (var output = new GZIPOutputStream(Files.newOutputStream(compressed))) {
            Files.copy(reference, output);
        }
        genome.getConfig().fastaURL = compressed.toString();
        assertOriginalIdAfterSearch(id);
    }

    @Test public void exportedEnaReferenceRetainsIdentifier() throws Exception {
        String id = "ENA|HM106493|HM106493.1";
        renameReference(id);
        genome.getConfig().fastaURL = null;
        assertOriginalIdAfterSearch(id);
    }

    @Test public void ncbiFormattedIdentifierIsNotReducedToAccession() throws Exception {
        String id = "gb|HM106493.1|LOCUS";
        renameReference(id);
        assertOriginalIdAfterSearch(id);
    }

    @Test public void longCustomIdentifierDoesNotExceedBlastIdLimit() throws Exception {
        String id = "custom_" + "a".repeat(100);
        renameReference(id);
        assertOriginalIdAfterSearch(id);
    }

    @Test public void failedBuildDoesNotPublishDatabaseOrLeaveTemporaryFiles() throws Exception {
        Files.writeString(reference, "");
        Path desired = cache.resolve("failed");
        IOException error = assertThrows(IOException.class, () -> prepare(desired.toString()));
        assertTrue(error.getMessage(), error.getMessage().contains("makeblastdb"));
        assertFalse(Files.exists(Path.of(desired + ".igv-ready")));
        assertFalse(Files.exists(Path.of(desired + ".igv-building")));
        try (var files = Files.list(cache)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".igv-build-")));
        }
    }

    @Test public void simultaneousSearchesBuildReferenceOnlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var count = new AtomicInteger();
        try {
            java.util.concurrent.Callable<Path> prepare = () -> BlastDatabase.prepare(genome, "", blastn.toString(), blastn, 30,
                    message -> { if (message.startsWith("Creating")) count.incrementAndGet(); }, cache);
            var one = executor.submit(prepare);
            var two = executor.submit(prepare);
            assertEquals(one.get(40, TimeUnit.SECONDS), two.get(40, TimeUnit.SECONDS));
            assertEquals(1, count.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
