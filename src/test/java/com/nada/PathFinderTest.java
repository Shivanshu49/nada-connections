package com.nada;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathFinderTest {
    private final PersonStore store = new PersonStore();
    private final PathFinder finder = new PathFinder(store);

    @Test
    void chainOfThreeHopsIsConnected() {
        List<Person> chain = chain("A", "B", "C", "D");

        List<Person> path = finder.find(id(chain, 0), id(chain, 3)).orElseThrow();

        assertEquals(3, path.size() - 1);
        assertEquals(chain, path);
    }

    @Test
    void chainOfFourHopsIsNotConnected() {
        List<Person> chain = chain("A", "B", "C", "D", "E");

        assertTrue(finder.find(id(chain, 0), id(chain, 4)).isEmpty());
    }

    @Test
    void shortcutMakesPathShorter() {
        List<Person> chain = chain("A", "B", "C", "D");
        store.connect(id(chain, 0), id(chain, 2));

        List<Person> path = finder.find(id(chain, 0), id(chain, 3)).orElseThrow();

        assertEquals(List.of(chain.get(0), chain.get(2), chain.get(3)), path);
    }

    @Test
    void sameStartAndEndIsZeroHops() {
        Person asha = store.create("Asha");

        assertEquals(List.of(asha), finder.find(asha.id(), asha.id()).orElseThrow());
    }

    @Test
    void isolatedPersonIsNotConnected() {
        List<Person> chain = chain("A", "B");
        Person loner = store.create("Loner");

        assertTrue(finder.find(id(chain, 0), loner.id()).isEmpty());
    }

    @Test
    void maxHopsIsConfigurable() {
        List<Person> chain = chain("A", "B", "C", "D", "E");

        List<Person> path = new PathFinder(store, 4).find(id(chain, 0), id(chain, 4)).orElseThrow();

        assertEquals(chain, path);
    }

    /** Creates people with the given names and connects each one to the next. */
    private List<Person> chain(String... names) {
        List<Person> people = Arrays.stream(names).map(store::create).toList();
        for (int i = 1; i < people.size(); i++) {
            store.connect(id(people, i - 1), id(people, i));
        }
        return people;
    }

    private static long id(List<Person> people, int index) {
        return people.get(index).id();
    }
}
