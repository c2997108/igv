package org.broad.igv.blast;

import org.broad.igv.DirectoryManager;
import org.broad.igv.feature.Strand;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.util.FileDialogUtils;
import org.broad.igv.ui.util.MessageUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** BLAST statistics in one-based coordinates, with navigation and tabular export. */
public class BlastQueryWindow extends JFrame {
    public BlastQueryWindow(Component parent, String database, String query, List<BlastHit> hits, Genome genome) {
        super("BLAST results");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTable table = new JTable(new HitTableModel(hits));
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || table.getSelectedRow() < 0) return;
            BlastHit hit = hits.get(table.convertRowIndexToModel(table.getSelectedRow()));
            String chromosome = hit.resolveChromosome(genome);
            if (chromosome == null || GenomeManager.getInstance().getCurrentGenome() != genome) return;
            int padding = (hit.getEnd() - hit.getStart()) / 4;
            int first = Math.max(1, hit.getStart() + 1 - padding);
            int last = (int) Math.min(genome.getChromosome(chromosome).getLength(), (long) hit.getEnd() + padding);
            IGV.getInstance().goToLocus(chromosome + ":" + first + "-" + last);
        });
        long unmapped = hits.stream().filter(h -> h.resolveChromosome(genome) == null).count();
        JTextArea header = new JTextArea("Database: " + database + "\nQuery: " + query.length() + " bases; " + hits.size() + " hits. Select a row to go to its alignment.\n" +
                (unmapped == 0 ? "" : unmapped + " hits have subject IDs absent from the loaded genome; these hits cannot be plotted or navigated."));
        header.setEditable(false);
        header.setLineWrap(true);
        header.setWrapStyleWord(true);
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JButton save = new JButton("Save results...");
        save.addActionListener(e -> {
            File file = FileDialogUtils.chooseFile("Save BLAST results", DirectoryManager.getUserDefaultDirectory(), FileDialogUtils.SAVE);
            if (file != null) {
                try {
                    saveResults(file, hits);
                } catch (IOException ex) {
                    MessageUtils.showErrorMessage("Error saving BLAST results", ex);
                }
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(save);
        add(buttons, BorderLayout.SOUTH);
        setSize(1100, 550);
        setLocationRelativeTo(parent);
    }

    public static void saveResults(File file, List<BlastHit> hits) throws IOException {
        try (var writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            for (BlastHit hit : hits) {
                writer.write(hit.getText());
                writer.newLine();
            }
        }
    }

    static class HitTableModel extends AbstractTableModel {
        private final List<BlastHit> hits;
        private final String[] columns = {"Subject", "Start (1-based)", "End (inclusive)", "Strand", "Identity (%)", "Length", "Query start", "Query end", "E-value", "Bit score"};

        HitTableModel(List<BlastHit> hits) { this.hits = hits; }
        @Override public int getRowCount() { return hits.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0, 3 -> String.class;
                case 4, 8, 9 -> Double.class;
                default -> Integer.class;
            };
        }
        @Override public Object getValueAt(int row, int column) {
            BlastHit hit = hits.get(row);
            return switch (column) {
                case 0 -> hit.getSubjectId();
                case 1 -> hit.getStart() + 1;
                case 2 -> hit.getEnd();
                case 3 -> hit.getStrand() == Strand.POSITIVE ? "+" : "-";
                case 4 -> hit.getIdentity();
                case 5 -> hit.getAlignmentLength();
                case 6 -> hit.getQueryStart();
                case 7 -> hit.getQueryEnd();
                case 8 -> hit.getEvalue();
                case 9 -> hit.getBitScore();
                default -> "";
            };
        }
    }
}
