package org.broad.igv.blast;

import org.broad.igv.feature.Strand;
import org.broad.igv.track.SequenceTrack;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in tests using real BLAST+ binaries: -Digv.blast.bin=/path/to/blast/bin */
public class LocalBlastIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private LocalBlastClient.Settings settings;
    private String query;

    @Before public void createDatabase() throws Exception {
        String binaries = System.getProperty("igv.blast.bin");
        assumeTrue("Set igv.blast.bin to run real BLAST+ integration tests", binaries != null);
        String extension = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        Path source = temporary.newFolder("source").toPath();
        Path directory = temporary.newFolder("database with spaces").toPath();
        Path fasta = source.resolve("reference.fa");
        Path database = directory.resolve("reference");
        Random random = new Random(1234);
        StringBuilder reference = new StringBuilder();
        for (int i = 0; i < 500; i++) reference.append("ACGT".charAt(random.nextInt(4)));
        query = reference.substring(100, 220);
        Files.writeString(fasta, ">chrCustom\n" + reference + "\n>lowComplexity\n" + "A".repeat(200) + "\n");
        Process process = new ProcessBuilder(Path.of(binaries, "makeblastdb" + extension).toString(),
                "-in", "reference.fa", "-dbtype", "nucl", "-parse_seqids", "-out", "reference")
                .directory(source.toFile())
                .redirectErrorStream(true).redirectOutput(source.resolve("makeblastdb.log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(Files.readString(source.resolve("makeblastdb.log")), 0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        // makeblastdb 2.17 can build files but fails its final validation under a directory with spaces.
        // Create the fixture in a plain directory, then verify blastn searches it at a path with spaces.
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) Files.copy(file, directory.resolve(file.getFileName()));
        }
        settings = new LocalBlastClient.Settings(Path.of(binaries, "blastn" + extension).toString(),
                database.toString(), "auto", 10, 100, 1, 30);
    }

    @Test public void searchesForwardAgainstDatabaseWithSpaces() throws Exception {
        var hits = LocalBlastClient.search(settings, query, null);
        assertFalse(hits.isEmpty());
        BlastHit best = hits.getFirst();
        assertEquals(100, best.getStart());
        assertEquals(220, best.getEnd());
        assertEquals(Strand.POSITIVE, best.getStrand());
        assertEquals(100, best.getIdentity(), 0);
        assertTrue(best.getSubjectId().endsWith("chrCustom"));
    }

    @Test public void searchesReverseComplement() throws Exception {
        var hits = LocalBlastClient.search(settings, SequenceTrack.getReverseComplement(query), null);
        assertFalse(hits.isEmpty());
        assertEquals(100, hits.getFirst().getStart());
        assertEquals(220, hits.getFirst().getEnd());
        assertEquals(Strand.NEGATIVE, hits.getFirst().getStrand());
    }

    @Test public void searchesShortQueries() throws Exception {
        var hits = LocalBlastClient.search(settings, query.substring(0, 25), null);
        assertTrue(hits.stream().anyMatch(h -> h.getStart() == 100 && h.getEnd() == 125 && h.getIdentity() == 100));
    }

    @Test public void noHitsProducesEmptyList() throws Exception {
        assertTrue(LocalBlastClient.search(settings, "N".repeat(100), null).isEmpty());
    }

    @Test public void disablingDustFindsRepeatsThatEnabledDustMasks() throws Exception {
        String repeat = "A".repeat(100);
        var hits = LocalBlastClient.search(settings, repeat, null);
        assertTrue(hits.stream().anyMatch(h -> h.getSubjectId().endsWith("lowComplexity") &&
                h.getIdentity() == 100 && h.getAlignmentLength() == 100));
        var filtered = new LocalBlastClient.Settings(settings.executable(), settings.database(), settings.task(),
                settings.evalue(), settings.maxTargets(), settings.threads(), settings.timeoutSeconds(), true);
        assertTrue(LocalBlastClient.search(filtered, repeat, null).isEmpty());
    }

    @Test public void missingDatabaseIncludesBlastDiagnostic() {
        var missing = new LocalBlastClient.Settings(settings.executable(), settings.database() + "-missing", "auto", 10, 100, 1, 30);
        IOException error = assertThrows(IOException.class, () -> LocalBlastClient.search(missing, query, null, ProcessBuilder::start));
        assertTrue(error.getMessage(), error.getMessage().contains("exited with code"));
        assertTrue(error.getMessage(), error.getMessage().contains("BLAST Database error"));
    }
}
