package org.minimallycorrect.javatransformer.internal.util;

import java.util.function.Supplier;
import java.util.stream.Stream;

public interface Splitter {
	Splitter commaSplitter = on(',');

	static Splitter on(char c) {
		return s -> CollectionUtil.stream(new Supplier<String>() {
			int base = -1;

			@Override
			public String get() {
				while (base != 0) {
					int base = this.base;
					if (base == -1)
						base = 0;

					int next = s.indexOf(c, base);

					int nextBase = next + 1;

					if (next == -1)
						next = s.length();

					String part = s.substring(base, next).trim();

					this.base = nextBase;

					if (!part.isEmpty())
						return part;
				}

				return null;
			}
		});
	}

	Stream<String> split(String s);

	default Iterable<String> splitIterable(String s) {
		return split(s)::iterator;
	}
}
