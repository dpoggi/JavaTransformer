package org.minimallycorrect.javatransformer.internal.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import lombok.experimental.UtilityClass;
import lombok.val;

@UtilityClass
public class CollectionUtil {
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> Stream<T> union(Collection<T>... collections) {
		return union(Arrays.asList(collections));
	}

	public static <T> Stream<T> union(Collection<Collection<T>> collections) {
		return collections.stream().flatMap(x -> x == null ? Stream.empty() : x.stream());
	}

	public static <T> Stream<T> stream(Supplier<T> supplier) {
		return stream(iterable(supplier));
	}

	public static <T> Stream<T> stream(Iterable<T> iterable) {
		if (iterable instanceof Collection)
			return ((Collection<T>) iterable).stream();

		return StreamSupport.stream(iterable.spliterator(), false);
	}

	public static <T> Iterable<T> iterable(Stream<T> supplier) {
		return supplier::iterator;
	}

	public static <T> Iterable<T> iterable(Supplier<T> supplier) {
		return () -> new IteratorFromSupplier<>(supplier);
	}

	public static <T> boolean equals(Collection<T> a, Collection<T> b, Comparer<T> c) {
		if (a.size() != b.size())
			return false;
		val bIterator = b.iterator();
		for (val aa : a) {
			val bb = bIterator.next();
			if (!c.compare(aa, bb))
				return false;
		}
		return true;
	}

	@FunctionalInterface
	public interface Comparer<T> {
		boolean compare(T a, T b);
	}

	private static class IteratorFromSupplier<T> implements Iterator<T> {
		private final Supplier<T> supplier;
		private T next;

		public IteratorFromSupplier(Supplier<T> supplier) {
			this.supplier = supplier;
			next = supplier.get();
		}

		@Override
		public boolean hasNext() {
			return next != null;
		}

		@Override
		public T next() {
			if (next == null)
				throw new NoSuchElementException();

			try {
				return next;
			} finally {
				next = supplier.get();
			}
		}
	}
}
