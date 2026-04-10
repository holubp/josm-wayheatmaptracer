package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.Icon;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NodeMove;
import org.openstreetmap.josm.tools.ImageProvider;

public final class MoveNodesCommand extends Command {
    private final List<NodeMove> nodeMoves;
    private final String description;

    public MoveNodesCommand(DataSet dataSet, List<NodeMove> nodeMoves, String description) {
        super(dataSet);
        this.nodeMoves = List.copyOf(nodeMoves);
        this.description = description;
    }

    @Override
    public boolean executeCommand() {
        super.executeCommand();
        for (NodeMove move : nodeMoves) {
            move.node().setEastNorth(move.target());
            move.node().setModified(true);
        }
        return true;
    }

    @Override
    public void fillModifiedData(Collection<OsmPrimitive> modified, Collection<OsmPrimitive> deleted, Collection<OsmPrimitive> added) {
        for (NodeMove move : nodeMoves) {
            modified.add(move.node());
        }
    }

    @Override
    public String getDescriptionText() {
        return description;
    }

    @Override
    public Icon getDescriptionIcon() {
        return new ImageProvider("dialogs", "search").get();
    }

    @Override
    public Collection<? extends OsmPrimitive> getParticipatingPrimitives() {
        List<Node> nodes = new ArrayList<>(nodeMoves.size());
        for (NodeMove move : nodeMoves) {
            nodes.add(move.node());
        }
        return nodes;
    }
}

