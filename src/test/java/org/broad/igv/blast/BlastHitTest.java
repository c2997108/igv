package org.broad.igv.blast;

import org.broad.igv.feature.Strand;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.feature.genome.InMemorySequence;
import org.broad.igv.feature.genome.load.GenomeConfig;
import org.broad.igv.track.BlastTrack;
import org.broad.igv.event.IGVEventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BlastHitTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String FORWARD = "query\tchr1\t98.5\t100\t1\t1\t1\t100\t101\t200\t1e-30\t180.5";
    private static final String REVERSE = "query\tlcl|chr1\t99.0\t100\t1\t0\t1\t100\t200\t101\t0\t190";

    private Genome genome() throws IOException {
        GenomeConfig config = new GenomeConfig();
        config.id = "blast-test";
        config.setName("BLAST test");
        config.setSequence(new InMemorySequence("chr1", new byte[1000]));
        return new Genome(config);
    }

    @Test public void convertsInclusiveCoordinatesAndPreservesStatistics() throws IOException {
        BlastHit hit = BlastHit.parse(new StringReader(FORWARD), null).getFirst();
        assertEquals(100, hit.getStart());
        assertEquals(200, hit.getEnd());
        assertEquals(Strand.POSITIVE, hit.getStrand());
        assertEquals(98.5, hit.getIdentity(), 0);
        assertEquals(1e-30, hit.getEvalue(), 0);
        assertEquals(180.5, hit.getBitScore(), 0);
        assertEquals(FORWARD, hit.getText());
    }

    @Test public void reverseHitsAndLocalIdsMapToGenome() throws IOException {
        Genome genome = genome();
        BlastHit hit = BlastHit.parse(new StringReader(REVERSE), genome).getFirst();
        assertEquals("chr1", hit.getChr());
        assertEquals("lcl|chr1", hit.getSubjectId());
        assertEquals(100, hit.getStart());
        assertEquals(200, hit.getEnd());
        assertEquals(Strand.NEGATIVE, hit.getStrand());
    }

    @Test public void retainsUnmappedSubjectsWithoutInventingCoordinates() throws IOException {
        Genome genome = genome();
        BlastHit hit = BlastHit.parse(new StringReader(FORWARD.replace("chr1", "other_contig")), genome).getFirst();
        assertEquals("other_contig", hit.getChr());
        assertNull(hit.resolveChromosome(genome));
    }

    @Test public void generatedLocalIdsAreRestoredOnceWithOrWithoutLocalPrefix() throws IOException {
        String original = "ENA|HM106493|HM106493.1";
        Map<String, String> ids = Map.of("igvref_1", original, "igvref_2", "igvref_1");
        for (String internal : List.of("igvref_1", "lcl|igvref_1")) {
            BlastHit hit = BlastHit.parse(new StringReader(FORWARD.replace("chr1", internal)), null, ids).getFirst();
            assertEquals(original, hit.getSubjectId());
            assertEquals(FORWARD.replace("chr1", original), hit.getText());
        }
        BlastHit collision = BlastHit.parse(new StringReader(FORWARD.replace("chr1", "igvref_2")), null, ids).getFirst();
        assertEquals("igvref_1", collision.getSubjectId());
        assertEquals("igvref_1", BlastHit.parse(new StringReader(collision.getText()), null).getFirst().getSubjectId());
    }

    @Test public void externalDatabasesKeepTheirOriginalSubjectIds() throws IOException {
        String line = FORWARD.replace("chr1", "igvref_1");
        assertEquals(line, BlastHit.parse(new StringReader(line), null).getFirst().getText());
    }

    @Test public void commentsAndEmptyResultsAreValid() throws IOException {
        assertTrue(BlastHit.parse(new StringReader("# no hits\n\n"), null).isEmpty());
    }

    @Test public void rejectsMalformedOutputWithLineNumber() {
        IOException error = assertThrows(IOException.class, () -> BlastHit.parse(new StringReader("# header\ninvalid"), null));
        assertTrue(error.getMessage().contains("line 2"));
        assertThrows(IOException.class, () -> BlastHit.parse(new StringReader(FORWARD.replace("101\t200", "0\t200")), null));
    }

    @Test public void tableSortsStatisticsNumericallyAndDisplaysOneBasedStarts() throws IOException {
        var model = new BlastQueryWindow.HitTableModel(BlastHit.parse(new StringReader(FORWARD), null));
        assertEquals(101, model.getValueAt(0, 1));
        assertEquals(Double.class, model.getColumnClass(8));
        assertEquals(1e-30, (double) model.getValueAt(0, 8), 0);
    }

    @Test public void savedResultsKeepStandardBlastColumns() throws IOException {
        var file = temporary.newFile("results.tsv");
        BlastQueryWindow.saveResults(file, BlastHit.parse(new StringReader(FORWARD + "\n" + REVERSE), null));
        assertEquals(List.of(FORWARD, REVERSE), Files.readAllLines(file.toPath()));
    }

    @Test public void trackSessionRestoresSnapshotWithoutRunningBlast() throws Exception {
        Genome genome = genome();
        Genome previous = GenomeManager.getInstance().getCurrentGenome();
        GenomeManager.getInstance().setCurrentGenomeForTest(genome);
        BlastTrack original = null;
        BlastTrack restored = null;
        try {
            original = new BlastTrack("missing database", "ACGTACGT", BlastHit.parse(new StringReader(FORWARD + "\n" + REVERSE), genome), "BLAST test");
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            var element = document.createElement("Track");
            document.appendChild(element);
            original.marshalXML(document, element);
            var xml = new StringWriter();
            TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(xml));
            var parsed = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml.toString())));
            restored = new BlastTrack();
            restored.unmarshalXML(parsed.getDocumentElement(), 8);
            assertEquals("BLAST test", restored.getName());
            assertEquals(2, restored.getHits().size());
            assertEquals(REVERSE, restored.getHits().get(1).getText());
            assertEquals(Strand.NEGATIVE, restored.getHits().get(1).getStrand());
            assertNull(restored.getResourceLocator());
        } finally {
            if (original != null) IGVEventBus.getInstance().unsubscribe(original);
            if (restored != null) IGVEventBus.getInstance().unsubscribe(restored);
            GenomeManager.getInstance().setCurrentGenomeForTest(previous);
        }
    }
}
