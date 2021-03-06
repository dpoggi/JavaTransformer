package org.minimallycorrect.javatransformer.internal.util;

import static org.junit.Assert.assertArrayEquals;

import java.util.ArrayList;

import org.junit.Test;

public class SplitterTest {
	@Test
	public void testSplitter() throws Exception {
		testSplitter('.', "a.b.c.d", "a", "b", "c", "d");
		testSplitter('.', "a.b..c.d", "a", "b", "c", "d");
		testSplitter('.', "a.b...c..d", "a", "b", "c", "d");
		testSplitter('.', ".a.b...c..d", "a", "b", "c", "d");
		testSplitter('.', "a.b...c..d.", "a", "b", "c", "d");
		testSplitter('.', ".a.b...c..d.", "a", "b", "c", "d");
	}

	private void testSplitter(char on, String input, String... expected) {
		assertArrayEquals(expected, toArray(Splitter.on(on).splitIterable(input)));
	}

	private String[] toArray(Iterable<String> split) {
		ArrayList<String> list = new ArrayList<>();

		split.forEach(list::add);

		return list.toArray(new String[0]);
	}
}
