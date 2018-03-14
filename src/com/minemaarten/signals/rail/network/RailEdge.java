package com.minemaarten.signals.rail.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.Validate;

import com.google.common.collect.ImmutableList;
import com.minemaarten.signals.rail.RailObjectHolder;

/**
 * Edge used in pathfinding. Edges may be unidirectional as a result of Signals, and Rail Links.
 * Edges do not have intersections (or they would be splitted into multiple edges) 
 * @author Maarten
 *
 * @param <TPos>
 */
public class RailEdge<TPos extends IPosition<TPos>> implements Iterable<NetworkRail<TPos>>{

    private final RailObjectHolder<TPos> railObjects;
    private final ImmutableList<NetworkRail<TPos>> edge;
    /**
     * The start and end pos, which end in an intersection. 
     */
    public final TPos startPos, endPos;

    /**
     * Outwards heading
     */
    public final EnumHeading startHeading, endHeading;

    /**
     * Length in amount of rails (blocks, realistically), used as weights in pathfinding
     */
    public final int length;

    /**
     * When true, trains can only be routed from startPos to endPos, not the other way around.
     */
    public final boolean unidirectional;

    private static enum EnumDirectionalityResult{
        /**
         * Trains can be routed both ways
         */
        BIDIRECTIONAL,
        /**
         * Trains can be routed one way only, and the given order is good
         */
        UNIDIRECTIONAL_NO_CHANGE,
        /**
         * Trains can be routed one way only, and the order needs to be reversed
         */
        UNIDIRECTIONAL_REVERSE,
        /**
         * Signals are placed in opposite directions, trains cannot be routed through here at all.
         */
        ZERODIRECTIONAL
    }

    public RailEdge(RailObjectHolder<TPos> railObjects, ImmutableList<NetworkRail<TPos>> edge){
        this.railObjects = railObjects;

        switch(determineDirectionality(edge)){
            case BIDIRECTIONAL:
                unidirectional = false;
                break;
            case UNIDIRECTIONAL_NO_CHANGE:
                unidirectional = true;
                break;
            case UNIDIRECTIONAL_REVERSE:
                unidirectional = true;
                edge = edge.reverse();
                break;
            case ZERODIRECTIONAL:
                unidirectional = false;//TODO
                break;
            default:
                throw new IllegalStateException();
        }

        TPos firstPos = edge.get(0).pos;
        TPos lastPos = edge.get(edge.size() - 1).pos;
        if(firstPos.equals(lastPos)) throw new IllegalStateException("First and last pos may not be equal! First: " + firstPos + ", Last: " + lastPos);

        //if bidirectional, save in a deterministic form for equals/hashcode purposes
        if(!unidirectional) {
            int compareResult = firstPos.compareTo(lastPos);
            if(compareResult > 0) {
                //Reverse the order
                edge = edge.reverse();
                firstPos = edge.get(0).pos;
                lastPos = edge.get(edge.size() - 1).pos;
            } else if(compareResult == 0) throw new IllegalStateException("Different positions should not return 0 for IComparable! First: " + firstPos + ", Last: " + lastPos);
        }

        this.edge = edge;
        startPos = firstPos;
        endPos = lastPos;
        startHeading = startPos.getRelativeHeading(edge.get(1).pos);
        endHeading = endPos.getRelativeHeading(edge.get(edge.size() - 2).pos);

        length = edge.size();
    }

    /**
     * Check connecting signals and determine which way trains can be routed.
     * @param edge
     * @return
     */
    private EnumDirectionalityResult determineDirectionality(ImmutableList<NetworkRail<TPos>> edge){
        boolean forwardsOk = true;
        boolean backwardsOk = true;
        for(int i = 1; i < edge.size() - 1; i++) {
            NetworkRail<TPos> prev = edge.get(i - 1);
            NetworkRail<TPos> cur = edge.get(i);
            NetworkRail<TPos> next = edge.get(i + 1);
            EnumHeading prevDir = cur.pos.getRelativeHeading(prev.pos);
            EnumHeading nextDir = next.pos.getRelativeHeading(cur.pos);

            if(prevDir == nextDir) { //Only evaluate signals on a straight
                List<NetworkSignal<TPos>> signals = railObjects.getNeighborSignals(cur.getPotentialNeighborObjectLocations()).collect(Collectors.toList());
                for(NetworkSignal<TPos> signal : signals) {
                    if(signal.heading == nextDir) {
                        backwardsOk = false;
                    } else if(signal.heading == nextDir.getOpposite()) {
                        forwardsOk = false;
                    }
                }
            }
        }

        if(backwardsOk && forwardsOk) return EnumDirectionalityResult.BIDIRECTIONAL;
        if(backwardsOk) return EnumDirectionalityResult.UNIDIRECTIONAL_REVERSE;
        if(forwardsOk) return EnumDirectionalityResult.UNIDIRECTIONAL_NO_CHANGE;
        return EnumDirectionalityResult.ZERODIRECTIONAL;
    }

    public TPos get(int index){
        return edge.get(index).pos;
    }

    public TPos other(TPos pos){
        if(pos.equals(startPos)) return endPos;
        if(pos.equals(endPos)) return startPos;
        throw new IllegalArgumentException("Pos " + pos + "not a start or end pos of edge " + this);
    }

    public EnumHeading headingForEndpoint(TPos pos){
        if(pos.equals(startPos)) return startHeading;
        if(pos.equals(endPos)) return endHeading;
        throw new IllegalArgumentException("Pos " + pos + "not a start or end pos of edge " + this);
    }

    /**
     * Create fake edges to connect to the startPos or endPos.
     * 
     * s      d       e
     * |------|-------|
     * 
     * creates two edges with
     * 
     * s      e
     * |------|
     * 
     * and
     *        e       s
     *        |-------|
     * 
     * Only edges that can accept a direction from start to end will be added
     * 
     * @param destination
     * @return
     */
    public Collection<RailEdge<TPos>> createEntryPoints(TPos destination){
        int destinationIndex = getIndex(destination);
        List<RailEdge<TPos>> entryPoints = new ArrayList<>(2);

        entryPoints.add(subEdge(0, destinationIndex));
        RailEdge<TPos> subEdge = subEdge(destinationIndex, edge.size() - 1);
        if(!subEdge.unidirectional) {
            //If unidirectional, we can't add the reversed edge, else we can.
            entryPoints.add(subEdge);
        }

        return entryPoints;
    }

    /**
     * Creates a single fake edge to connect to the startPos or endPos.
     * 
     * s      f->     e
     * |------|-------|
     * 
     * creates a single edge with
     *        s       e
     *        |-------|
     * 
     * Only edges that can accept a direction from start to end will be added, and that match the given direction
     */
    public RailEdge<TPos> createExitPoint(TPos from, EnumHeading direction){
        int destinationIndex = getIndex(from);
        TPos nextNeighbor = edge.get(destinationIndex + 1).pos;
        if(direction == null || nextNeighbor.getRelativeHeading(from) == direction) {
            return subEdge(destinationIndex, edge.size() - 1);
        } else {
            TPos prevNeighbor = edge.get(destinationIndex - 1).pos;
            if(prevNeighbor.getRelativeHeading(from) == direction) {
                RailEdge<TPos> subEdge = subEdge(0, destinationIndex);
                if(!subEdge.unidirectional) return subEdge;//When not unidirectional we can evaluate 'f -> s'
            }
        }
        return null;
    }

    private int getIndex(TPos pos){
        NetworkObject<TPos> destObj = railObjects.get(pos);
        Validate.notNull(destObj);
        int destinationIndex = edge.indexOf(destObj);
        if(destinationIndex < 0) throw new IllegalStateException("Edge " + this + " does not contain " + pos);
        return destinationIndex;
    }

    @Override
    public Iterator<NetworkRail<TPos>> iterator(){
        return edge.iterator();
    }

    private RailEdge<TPos> subEdge(int startIndex, int endIndex){
        ImmutableList<NetworkRail<TPos>> subEdge = edge.subList(startIndex, endIndex + 1);
        //Map<TPos, NetworkObject<TPos>> allObjects = new HashMap<>(allNetworkObjects);
        //subEdge.forEach(r -> allObjects.remove(r.pos)); //TODO prune non-rail objects better.
        return new RailEdge<TPos>(railObjects, subEdge);
    }

    @Override
    public String toString(){
        return startPos + " -> " + endPos;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean equals(Object other){
        if(other instanceof RailEdge) {
            RailEdge<TPos> edge = (RailEdge<TPos>)other;
            return startPos.equals(edge.startPos) && endPos.equals(edge.endPos) && startHeading.equals(edge.startHeading) && endHeading.equals(edge.endHeading) && unidirectional == edge.unidirectional;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode(){
        int hash = startPos.hashCode() * 13;
        hash = hash * 13 + endPos.hashCode();
        hash = hash * 13 + startHeading.hashCode();
        hash = hash * 13 + endHeading.hashCode();
        return hash;
    }
}