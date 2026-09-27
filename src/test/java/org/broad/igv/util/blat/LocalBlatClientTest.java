package org.broad.igv.util.blat;

import org.broad.igv.feature.Strand;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.InMemorySequence;
import org.broad.igv.feature.genome.load.GenomeConfig;
import org.broad.igv.feature.genome.load.FastaGenomeLoader;
import org.broad.igv.track.SequenceTrack;
import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

public class LocalBlatClientTest {
    private Path executable() {
        String bin = System.getProperty("igv.blat.bin");
        assumeNotNull(bin);
        return Path.of(bin, System.getProperty("os.name").startsWith("Windows") ? "blat.exe" : "blat");
    }

    private String bases() {
        var random = new Random(219);
        var result = new StringBuilder();
        for (int i = 0; i < 1000; i++) result.append("ACGT".charAt(random.nextInt(4)));
        return result.toString();
    }

    @Test public void enforcesSequenceAndTimeoutLimitsBeforeStartingProcess() {
        Path unused = Path.of("not-a-tool");
        assertThrows(IllegalArgumentException.class, () -> LocalBlatClient.search(unused, "db", "A".repeat(19), null, 60));
        assertThrows(IllegalArgumentException.class, () -> LocalBlatClient.search(unused, "db", "A".repeat(8001), null, 60));
        assertThrows(IllegalArgumentException.class, () -> LocalBlatClient.search(unused, "db", "A".repeat(100), null, 0));
    }

    @Test public void interruptionDoesNotStartProcess() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> LocalBlatClient.search(Path.of("not-a-tool"), "db", "A".repeat(100), null, 60));
        } finally { Thread.interrupted(); }
    }

    @Test public void searchesFastaPathsWithSpacesAndPreservesOriginalIdAndBothStrands() throws Exception {
        Path tool = executable();
        Path directory = Files.createTempDirectory("blat reference with spaces ");
        Path fasta = directory.resolve("reference with spaces.fa");
        String sequence = bases();
        String chromosome = "ENA|HM106493|HM106493.1";
        try {
            Files.writeString(fasta, ">" + chromosome + " description\n" + sequence + "\n");
            Genome genome = new FastaGenomeLoader(fasta.toString()).loadGenome();
            var forward = LocalBlatClient.search(tool, fasta.toString(), ">query\n" + sequence.substring(200, 350), genome, 60).getFirst();
            assertEquals(chromosome, forward.getChr());
            assertEquals(200, forward.getStart());
            assertEquals(350, forward.getEnd());
            assertEquals(Strand.POSITIVE, forward.getStrand());
            var reverse = LocalBlatClient.search(tool, genome.getId(), SequenceTrack.getReverseComplement(sequence.substring(200, 350)), genome, 60).getFirst();
            assertEquals(200, reverse.getStart());
            assertEquals(350, reverse.getEnd());
            assertEquals(Strand.NEGATIVE, reverse.getStrand());
        } finally {
            Files.deleteIfExists(Path.of(fasta + ".fai"));
            Files.deleteIfExists(fasta);
            Files.deleteIfExists(directory);
        }
    }

    @Test public void exportsLoadedCompressedReferenceWithoutRequiringGzipProgram() throws Exception {
        Path tool = executable();
        Path fasta = Files.createTempFile("blat-compressed", ".fa.gz");
        String sequence = bases();
        try {
            try (var output = new GZIPOutputStream(Files.newOutputStream(fasta))) {
                output.write((">custom_id\n" + sequence + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            var config = new GenomeConfig();
            config.id = "compressed-reference";
            config.setName(config.id);
            config.fastaURL = fasta.toString();
            config.setSequence(new InMemorySequence("custom_id", sequence.getBytes(StandardCharsets.US_ASCII)));
            var hit = LocalBlatClient.search(tool, config.id, sequence.substring(200, 350), new Genome(config), 60).getFirst();
            assertEquals("custom_id", hit.getChr());
            assertEquals(200, hit.getStart());
            assertEquals(350, hit.getEnd());
        } finally { Files.deleteIfExists(fasta); }
    }

    @Test public void reportsNativeFailureInsteadOfAnEmptyResult() throws Exception {
        Path tool = executable();
        Path fasta = Files.createTempFile("blat-invalid", ".fa");
        try {
            Files.writeString(fasta, "invalid FASTA\n");
            IOException error = assertThrows(IOException.class, () -> LocalBlatClient.search(tool, fasta.toString(), "A".repeat(100), null, 60));
            assertTrue(error.getMessage().contains("BLAT exited with code"));
        } finally { Files.deleteIfExists(fasta); }
    }
}
