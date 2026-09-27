package org.broad.igv.track;

import org.broad.igv.blast.BlastHit;
import org.broad.igv.blast.BlastQueryWindow;
import org.broad.igv.event.DataLoadedEvent;
import org.broad.igv.event.IGVEventBus;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.renderer.IGVFeatureRenderer;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.panel.IGVPopupMenu;
import org.broad.igv.ui.panel.ReferenceFrame;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** A snapshot of local BLAST results; session restore does not need BLAST+ or its database. */
public class BlastTrack extends FeatureTrack {
    private String database;
    private String sequence;
    private List<BlastHit> hits;

    public BlastTrack() { }

    public BlastTrack(String database, String sequence, List<BlastHit> hits, String label) {
        super(null, "blast-" + UUID.randomUUID(), label);
        this.database = database;
        this.sequence = sequence;
        this.hits = hits;
        setDisplayMode(DisplayMode.SQUISHED);
        setColor(Color.DARK_GRAY);
        initResults();
    }

    private void initResults() {
        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        source = new FeatureCollectionSource(hits.stream().filter(h -> h.resolveChromosome(genome) != null).toList(), genome);
        renderer = new IGVFeatureRenderer();
        // Identity is already available in the tooltip/table; keep HSP intervals clearly visible.
        setUseScore(false);
        IGVEventBus.getInstance().subscribe(DataLoadedEvent.class, this);
    }

    public List<BlastHit> getHits() { return hits; }

    @Override protected boolean isShowFeatures(ReferenceFrame frame) { return true; }

    @Override public IGVPopupMenu getPopupMenu(TrackClickEvent event) {
        IGVPopupMenu menu = TrackMenuUtils.getPopupMenu(Arrays.asList(this), "Menu", event);
        JMenuItem table = new JMenuItem("Open table view");
        table.addActionListener(e -> new BlastQueryWindow(IGV.getInstance().getMainFrame(), database, sequence, hits,
                GenomeManager.getInstance().getCurrentGenome()).setVisible(true));
        menu.addSeparator();
        menu.add(table);
        return menu;
    }

    @Override public void marshalXML(Document document, Element element) {
        super.marshalXML(document, element);
        element.setAttribute("blastDatabase", database);
        element.setAttribute("blastSequence", sequence);
        Element results = document.createElement("BlastResults");
        results.setTextContent(hits.stream().map(BlastHit::getText).collect(Collectors.joining("\n")));
        element.appendChild(results);
    }

    @Override public void unmarshalXML(Element element, Integer version) {
        super.unmarshalXML(element, version);
        database = element.getAttribute("blastDatabase");
        sequence = element.getAttribute("blastSequence");
        var results = element.getElementsByTagName("BlastResults");
        String text = results.getLength() == 0 ? "" : results.item(0).getTextContent();
        try {
            hits = BlastHit.parse(new StringReader(text), GenomeManager.getInstance().getCurrentGenome());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot restore BLAST results", e);
        }
        initResults();
    }
}
