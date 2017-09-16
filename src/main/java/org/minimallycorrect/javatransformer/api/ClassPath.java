package org.minimallycorrect.javatransformer.api;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashSet;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.val;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import org.minimallycorrect.javatransformer.internal.util.JVMUtil;
import org.minimallycorrect.javatransformer.internal.util.Splitter;

// TODO: make this faster by using dumb regexes instead of JavaParser?
// probably not worth doing as ClassPath is only used when parsing source files which isn't currently done at runtime
// TODO: Class metadata -> HashSet of names should instead be a map of names -> objects?
public class ClassPath {
	public static final ClassPath SYSTEM_CLASS_PATH = makeSystemClassPath();

	private final HashSet<String> classes = new HashSet<>();
	private final HashSet<Path> inputPaths = new HashSet<>();
	private final ClassPath parent;
	private final String prefix;
	private boolean loaded;

	private ClassPath(@Nullable ClassPath parent, @Nullable String prefix) {
		this.parent = parent;
		this.prefix = prefix;
	}

	private ClassPath(@Nullable ClassPath parent) {
		this(parent, null);
	}

	public ClassPath() {
		this(SYSTEM_CLASS_PATH);
	}

	public ClassPath(Collection<Path> paths) {
		this();
		addPaths(paths);
	}

	public ClassPath createChildWithExtraPaths(Collection<Path> paths) {
		val searchPath = new ClassPath(this);
		searchPath.addPaths(paths);
		return searchPath;
	}

	@Override
	public String toString() {
		initialise();
		return (parent == null ? "" : parent.toString() + ", ") + inputPaths;
	}

	/**
	 * Returns whether the given class name exists in this class path
	 *
	 * @param className class name in JLS format: package1.package2.ClassName, package1.package2.ClassName$InnerClass
	 * @return true if the class exists
	 */
	@Contract(value = "null -> fail", pure = true)
	public boolean classExists(@NonNull String className) {
		initialise();
		return classes.contains(className) || (parent != null && parent.classExists(className));
	}

	/**
	 * Adds a {@link Path} to this {@link ClassPath}
	 *
	 * @param path {@link Path} to add
	 * @return true if the path was added, false if the path already existed in this {@link ClassPath}
	 */
	@Contract(value = "null -> fail", pure = true)
	public boolean addPath(@NonNull Path path) {
		path = path.normalize().toAbsolutePath();
		val add = !parentHasPath(path) && inputPaths.add(path);
		if (add && loaded)
			loadPath(path);
		return add;
	}

	@Contract("null -> fail")
	public void addPaths(@NonNull Iterable<Path> paths) {
		for (Path path : paths)
			addPath(path);
	}

	/**
	 * @param path path must be normalized and absolute
	 */
	@Contract("null -> fail")
	private boolean parentHasPath(@NonNull Path path) {
		val parent = this.parent;
		return parent != null && (parent.inputPaths.contains(path) || parent.parentHasPath(path));
	}

	@SneakyThrows
	private void findPaths(String entryName, Supplier<InputStream> iss) {
		if (prefix != null && !entryName.startsWith(prefix))
			return;

		if (entryName.endsWith(".java"))
			try (val is = iss.get()) {
				findJavaPaths(is);
			}

		if (entryName.endsWith(".class"))
			classes.add(JVMUtil.fileNameToClassName(entryName));
	}

	private void findJavaPaths(InputStream is) {
		val parsed = JavaParser.parse(is);
		findJavaPaths(parsed);
	}

	private void findJavaPaths(CompilationUnit compilationUnit) {
		val typeNames = compilationUnit.getTypes();
		val packageDeclaration = compilationUnit.getPackageDeclaration().orElse(null);
		val prefix = packageDeclaration == null ? "" : packageDeclaration.getNameAsString() + '.';
		for (TypeDeclaration<?> typeDeclaration : typeNames)
			findJavaPaths(typeDeclaration, prefix);
	}

	private void findJavaPaths(TypeDeclaration<?> typeDeclaration, String packagePrefix) {
		val name = packagePrefix + typeDeclaration.getNameAsString();
		classes.add(name);
		for (val node : typeDeclaration.getChildNodes())
			if (node instanceof TypeDeclaration)
				findJavaPaths((TypeDeclaration<?>) node, name + '.');
	}

	private void initialise() {
		if (loaded)
			return;
		synchronized (this) {
			// this double checked locking is technically wrong?
			if (loaded)
				return;
			loaded = true;
			for (Path path : inputPaths)
				loadPath(path);
		}
	}

	@SneakyThrows
	private synchronized void loadPath(Path path) {
		if (Files.isDirectory(path))
			Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
				@SneakyThrows
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					val entryName = path.relativize(file).toString().replace(File.separatorChar, '/');
					findPaths(entryName, () -> {
						try {
							return Files.newInputStream(file);
						} catch (IOException e) {
							throw new IOError(e);
						}
					});
					return super.visitFile(file, attrs);
				}
			});
		else if (Files.isRegularFile(path))
			try (val zis = new ZipInputStream(Files.newInputStream(path))) {
				ZipEntry e;
				val is = new InputStream() {
					public int read(@NonNull byte[] b, int off, int len) throws IOException {
						return zis.read(b, off, len);
					}

					public void close() throws IOException {
						// don't allow closing this ZIS
					}

					public int read() throws IOException {
						return zis.read();
					}
				};
				while ((e = zis.getNextEntry()) != null) {
					try {
						findPaths(e.getName(), () -> is);
					} finally {
						zis.closeEntry();
					}
				}
			}
	}

	private static ClassPath makeSystemClassPath() {
		// only scan java/ files in boot class path
		// avoid JVM/JDK internals
		val classPath = new ClassPath(null, "java/");
		classPath.addPaths(Splitter.pathSplitter.split(ManagementFactory.getRuntimeMXBean().getBootClassPath()).map(it -> Paths.get(it)).filter(it -> it.getFileName().toString().equals("rt.jar"))::iterator);
		return classPath;
	}
}
