package com.nada;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Breadth-first search, one level (hop) at a time. BFS reaches every person by the fewest
 * hops first, so the first time we reach the target we have a shortest path.
 */
public class PathFinder {
    public static final int DEFAULT_MAX_HOPS = 3;

    private final PersonStore store;
    private final int maxHops;

    public PathFinder(PersonStore store) {
        this(store, DEFAULT_MAX_HOPS);
    }

    public PathFinder(PersonStore store, int maxHops) {
        this.store = store;
        this.maxHops = maxHops;
    }

    /** Shortest path from one person to another within maxHops, or empty if there is none. */
    public Optional<List<Person>> find(long from, long to) {
        store.require(from);
        store.require(to);

        // Each reached person points back to who we reached them from; doubles as the visited set.
        Map<Long, Long> parent = new HashMap<>();
        parent.put(from, from);
        List<Long> level = List.of(from);

        for (int hops = 0; hops < maxHops && !parent.containsKey(to); hops++) {
            level = nextLevel(level, parent);
        }
        return parent.containsKey(to) ? Optional.of(pathTo(from, to, parent)) : Optional.empty();
    }

    private List<Long> nextLevel(List<Long> level, Map<Long, Long> parent) {
        List<Long> next = new ArrayList<>();
        for (long id : level) {
            for (long neighbour : store.neighbours(id)) {
                if (!parent.containsKey(neighbour)) {
                    parent.put(neighbour, id);
                    next.add(neighbour);
                }
            }
        }
        return next;
    }

    private List<Person> pathTo(long from, long to, Map<Long, Long> parent) {
        List<Person> path = new ArrayList<>();
        for (long id = to; id != from; id = parent.get(id)) {
            path.add(store.require(id));
        }
        path.add(store.require(from));
        Collections.reverse(path);
        return path;
    }
}
