package org.broad.igv.blast;

import org.broad.igv.feature.BasicFeature;
import org.broad.igv.feature.Strand;
import org.broad.igv.feature.genome.Genome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One BLAST HSP. BLAST's inclusive, one-based coordinates become IGV's half-open coordinates. */
public class BlastHit extends BasicFeature {
    private final String text;
    private final String queryId;
    private final String subjectId;
    private final double identity;
    private final int alignmentLength;
    private final int queryStart;
    private final int queryEnd;
    private final double evalue;
    private final double bitScore;

    private BlastHit(String line) {
        String[] t = line.split("\t", -1);
        if (t.length != 12) throw new IllegalArgumentException("Expected 12 BLAST tabular columns");
        text = line;
        queryId = t[0];
        subjectId = t[1];
        identity = Double.parseDouble(t[2]);
        alignmentLength = Integer.parseInt(t[3]);
        queryStart = Integer.parseInt(t[6]);
        queryEnd = Integer.parseInt(t[7]);
        int subjectStart = Integer.parseInt(t[8]);
        int subjectEnd = Integer.parseInt(t[9]);
        evalue = Double.parseDouble(t[10]);
        bitScore = Double.parseDouble(t[11]);
        if (queryId.isEmpty() || subjectId.isEmpty() || queryStart < 1 || queryEnd < 1 ||
                subjectStart < 1 || subjectEnd < 1 || alignmentLength < 1 ||
                !Double.isFinite(identity) || identity < 0 || identity > 100 ||
                !Double.isFinite(evalue) || evalue < 0 || !Double.isFinite(bitScore)) {
            throw new IllegalArgumentException("Invalid BLAST hit values");
        }
        setChr(subjectId);
        setStart(Math.min(subjectStart, subjectEnd) - 1);
        setEnd(Math.max(subjectStart, subjectEnd));
        setStrand((queryStart <= queryEnd) == (subjectStart <= subjectEnd) ? Strand.POSITIVE : Strand.NEGATIVE);
        setName(queryId);
        setScore((float) identity);
        setAttribute("Identity (%)", t[2]);
        setAttribute("E-value", t[10]);
        setAttribute("Bit score", t[11]);
        setAttribute("Query interval", queryStart + "-" + queryEnd);
    }

    public static List<BlastHit> parse(Reader input, Genome genome) throws IOException {
        return parse(input, genome, Map.of());
    }

    static List<BlastHit> parse(Reader input, Genome genome, Map<String, String> subjectIds) throws IOException {
        BufferedReader reader = input instanceof BufferedReader ? (BufferedReader) input : new BufferedReader(input);
        List<BlastHit> hits = new ArrayList<>();
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) continue;
            try {
                if (!subjectIds.isEmpty()) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length == 12) {
                        String local = fields[1].startsWith("lcl|") ? fields[1].substring(4) : fields[1];
                        String original = subjectIds.get(local);
                        if (original != null) {
                            fields[1] = original;
                            line = String.join("\t", fields);
                        }
                    }
                }
                BlastHit hit = new BlastHit(line);
                if (genome != null) {
                    String chromosome = hit.resolveChromosome(genome);
                    if (chromosome != null) hit.setChr(chromosome);
                }
                hits.add(hit);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid BLAST output at line " + lineNumber + ": " + e.getMessage(), e);
            }
        }
        return hits;
    }

    public String resolveChromosome(Genome genome) {
        if (genome == null) return null;
        if (genome.getChromosome(subjectId) != null) return genome.getCanonicalChrName(subjectId);
        // makeblastdb -parse_seqids represents local FASTA identifiers as lcl|identifier.
        String localId = subjectId.startsWith("lcl|") ? subjectId.substring(4) : subjectId;
        return genome.getChromosome(localId) == null ? null : genome.getCanonicalChrName(localId);
    }

    public String getText() { return text; }
    public String getQueryId() { return queryId; }
    public String getSubjectId() { return subjectId; }
    public double getIdentity() { return identity; }
    public int getAlignmentLength() { return alignmentLength; }
    public int getQueryStart() { return queryStart; }
    public int getQueryEnd() { return queryEnd; }
    public double getEvalue() { return evalue; }
    public double getBitScore() { return bitScore; }
}
