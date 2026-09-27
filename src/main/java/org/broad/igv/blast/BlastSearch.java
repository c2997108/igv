package org.broad.igv.blast;

import org.broad.igv.feature.Strand;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.prefs.PreferencesManager;
import org.broad.igv.track.BlastTrack;
import org.broad.igv.track.SequenceTrack;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.util.MessageUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Swing entry points shared by the Tools menu and sequence context menus. */
public class BlastSearch {
    private static final Logger log = LogManager.getLogger(BlastSearch.class);

    public static void promptForSequence() {
        JTextArea input = new JTextArea(8, 60);
        input.setLineWrap(true);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("Enter a nucleotide sequence or a single FASTA record:"), BorderLayout.NORTH);
        panel.add(new JScrollPane(input), BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(IGV.getInstance().getMainFrame(), panel, "Local BLAST+ search",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            doBlastQuery(input.getText(), "Sequence");
        }
    }

    public static JMenuItem sequenceItem(String label, String sequence, String trackLabel) {
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(sequence != null && !sequence.equals("*") && sequence.length() >= LocalBlastClient.MINIMUM_SEQUENCE_LENGTH);
        item.addActionListener(e -> doBlastQuery(sequence, trackLabel));
        return item;
    }

    public static void doBlastQuery(String sequence, String trackLabel) {
        start(sequence, null, 0, 0, Strand.NONE, trackLabel);
    }

    public static void doBlastQueryFromRegion(String chr, int start, int end, Strand strand) {
        start(null, chr, start, end, strand, chr + ":" + (start + 1) + "-" + end);
    }

    private static void start(String input, String chr, int start, int end, Strand strand, String trackLabel) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> start(input, chr, start, end, strand, trackLabel));
            return;
        }
        final LocalBlastClient.Settings settings;
        final Genome genome = GenomeManager.getInstance().getCurrentGenome();
        if (genome == null) {
            MessageUtils.showMessage("Load a genome before searching with BLAST.");
            return;
        }
        try {
            settings = LocalBlastClient.Settings.fromPreferences(PreferencesManager.getPreferences());
            if (chr != null && (end <= start || end - start > LocalBlastClient.MAXIMUM_SEQUENCE_LENGTH)) {
                throw new IllegalArgumentException("Select a region of at most 1,000,000 bases for BLAST.");
            }
            if (input != null) LocalBlastClient.normalizeSequence(input);
        } catch (IllegalArgumentException e) {
            MessageUtils.showMessage(e.getMessage());
            return;
        }

        JDialog progress = new JDialog(IGV.getInstance().getMainFrame(), "BLAST search", false);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel status = new JLabel("Preparing local BLAST search...");
        panel.add(status, BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        panel.add(bar, BorderLayout.CENTER);
        JButton cancel = new JButton("Cancel");
        panel.add(cancel, BorderLayout.SOUTH);
        progress.add(panel);
        progress.pack();
        progress.setLocationRelativeTo(IGV.getInstance().getMainFrame());
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        SwingWorker<List<BlastHit>, String> worker = new SwingWorker<>() {
            private String query;
            private LocalBlastClient.Settings prepared;

            @Override
            protected List<BlastHit> doInBackground() throws Exception {
                query = input;
                if (chr != null) {
                    byte[] bases = genome.getSequence(chr, start, end);
                    if (bases == null || bases.length != end - start) throw new IllegalArgumentException("Reference sequence is unavailable for this region.");
                    query = new String(bases, StandardCharsets.US_ASCII);
                    if (strand == Strand.NEGATIVE) query = SequenceTrack.getReverseComplement(query);
                }
                query = LocalBlastClient.normalizeSequence(query);
                prepared = LocalBlastClient.prepare(settings, genome, this::publish);
                publish("Searching local BLAST database...");
                return LocalBlastClient.search(prepared, query, genome);
            }

            @Override
            protected void process(List<String> updates) {
                status.setText(updates.getLast());
                progress.pack();
            }

            @Override
            protected void done() {
                progress.dispose();
                try {
                    List<BlastHit> hits = get();
                    if (GenomeManager.getInstance().getCurrentGenome() != genome) {
                        MessageUtils.showMessage("The genome changed during the BLAST search. Run the search again against the current genome.");
                    } else if (hits.isEmpty()) {
                        MessageUtils.showMessage("No BLAST hits found.");
                    } else {
                        // Keep every hit in the table, but plot only subjects present in the loaded genome.
                        if (hits.stream().anyMatch(h -> h.resolveChromosome(genome) != null)) {
                            IGV.getInstance().addTrack(new BlastTrack(prepared.database(), query, hits, "BLAST: " + trackLabel));
                            IGV.getInstance().repaint();
                        }
                        new BlastQueryWindow(IGV.getInstance().getMainFrame(), prepared.database(), query, hits, genome).setVisible(true);
                    }
                } catch (CancellationException e) {
                    // User cancelled; the worker interrupts and terminates blastn.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.error("BLAST error", e.getCause());
                    // Use plain text: executable error messages can contain angle brackets.
                    JOptionPane.showMessageDialog(IGV.getInstance().getMainFrame(), e.getCause().getMessage(), "BLAST error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        cancel.addActionListener(e -> worker.cancel(true));
        progress.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { worker.cancel(true); }
        });
        worker.execute();
        progress.setVisible(true);
    }
}
