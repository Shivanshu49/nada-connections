package com.nada;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory people and their mutual connections. Safe to share across request threads. */
public class PersonStore {
    private final Map<Long, Person> people = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> adjacency = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    public Person create(String name) {
        Person person = new Person(ids.getAndIncrement(), name);
        // Adjacency first, so a person is never visible without a contact set.
        adjacency.put(person.id(), ConcurrentHashMap.newKeySet());
        people.put(person.id(), person);
        return person;
    }

    public Person require(long id) {
        Person person = people.get(id);
        if (person == null) {
            throw new ApiException(404, "no person " + id);
        }
        return person;
    }

    /** Idempotent: sets ignore duplicates, so repeating a connection is a no-op. */
    public void connect(long a, long b) {
        require(a);
        require(b);
        // The two adds are not one atomic step, so a concurrent reader may briefly see a one-way edge.
        // Fine for this API; wrap both in a lock if strict consistency is ever needed.
        adjacency.get(a).add(b);
        adjacency.get(b).add(a);
    }

    public Set<Long> neighbours(long id) {
        require(id);
        return Collections.unmodifiableSet(adjacency.get(id));
    }

    public List<Person> contacts(long id) {
        require(id);
        return adjacency.get(id).stream().sorted().map(people::get).toList();
    }
}
