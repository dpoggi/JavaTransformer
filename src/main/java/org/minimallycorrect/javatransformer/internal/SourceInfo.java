package org.minimallycorrect.javatransformer.internal;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.val;

import org.jetbrains.annotations.Nullable;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithParameters;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import org.minimallycorrect.javatransformer.api.AccessFlags;
import org.minimallycorrect.javatransformer.api.Annotation;
import org.minimallycorrect.javatransformer.api.ClassInfo;
import org.minimallycorrect.javatransformer.api.ClassPath;
import org.minimallycorrect.javatransformer.api.FieldInfo;
import org.minimallycorrect.javatransformer.api.MethodInfo;
import org.minimallycorrect.javatransformer.api.Parameter;
import org.minimallycorrect.javatransformer.api.TransformationException;
import org.minimallycorrect.javatransformer.api.Type;
import org.minimallycorrect.javatransformer.api.TypeVariable;
import org.minimallycorrect.javatransformer.internal.util.AnnotationParser;
import org.minimallycorrect.javatransformer.internal.util.CachingSupplier;
import org.minimallycorrect.javatransformer.internal.util.NodeUtil;

@Data
@AllArgsConstructor
@SuppressWarnings("unchecked")
public class SourceInfo implements ClassInfo {
	@Getter(AccessLevel.NONE)
	private final Supplier<TypeDeclaration<?>> type;
	@Getter(lazy = true)
	private final String packageName = getPackageNameInternal();
	private String className;
	private ClassPath classPath;
	@Getter(lazy = true)
	private final List<Annotation> annotations = getAnnotationsInternal();
	@Getter(lazy = true)
	private final ResolutionContext context = getContextInternal();

	static void changeTypeContext(ResolutionContext old, ResolutionContext new_, FieldDeclaration f) {
		val v = f.getVariable(0);
		v.setType(changeTypeContext(old, new_, f.getCommonType()));
		val e = v.getInitializer().orElse(null);
		if (e != null) {
			NodeUtil.forChildren(e, it -> {
				val t = (ClassOrInterfaceType) ResolutionContext.typeToJavaParserType(old.resolve(it));
				it.setName(t.getName());
				it.setTypeArguments(t.getTypeArguments().orElse(null));
			}, ClassOrInterfaceType.class);
		}
	}

	static com.github.javaparser.ast.type.Type changeTypeContext(ResolutionContext old, ResolutionContext new_, com.github.javaparser.ast.type.Type t) {
		Type current = old.resolve(t);
		if (current.isClassType()) {
			return ResolutionContext.typeToJavaParserType(current.remapClassNames(new_::typeToJavaParserType));
		}
		return t;
	}

	static void changeTypeContext(ResolutionContext old, ResolutionContext new_, MethodDeclaration m) {
		m.setType(changeTypeContext(old, new_, m.getType()));
		m.setBody(new BlockStmt(NodeList.nodeList(new ThrowStmt(new ObjectCreationExpr(null, ResolutionContext.nonGenericClassOrInterfaceType("UnsupportedOperationException"), NodeList.nodeList())))));
		NodeUtil.forChildren(m, node -> {
			Expression scope = node.getScope().orElse(null);
			// TODO: Currently guesses that it's a type name if first character is uppercase.
			// Should check for fields/variables which match instead
			if (scope instanceof NameExpr) {
				String name = ((NameExpr) scope).getName().asString();
				if (Character.isUpperCase(name.charAt(0)))
					node.setScope(new NameExpr(new_.typeToString(old.resolve(name))));
			}
		}, MethodCallExpr.class);
		NodeUtil.forChildren(m, node -> node.getVariable(0).setType(changeTypeContext(old, new_, node.getCommonType())), VariableDeclarationExpr.class);
		NodeUtil.forChildren(m, node -> node.setType(changeTypeContext(old, new_, node.getType())), TypeExpr.class);
		NodeUtil.forChildren(m, node -> node.setType(changeTypeContext(old, new_, node.getType())), com.github.javaparser.ast.body.Parameter.class);
	}

	private static List<Parameter> getParameters(NodeWithParameters<?> nodeWithParameters, Supplier<ResolutionContext> context) {
		return nodeWithParameters.getParameters().stream()
			.map((parameter) -> Parameter.of(context.get().resolve(parameter.getType()), parameter.getName().asString(), CachingSupplier.of(() -> parameter.getAnnotations().stream().map(it -> AnnotationParser.annotationFromAnnotationExpr(it, context.get())).collect(Collectors.toList()))))
			.collect(Collectors.toList());
	}

	@Nullable
	private ClassOrInterfaceDeclaration getClassOrInterfaceDeclaration() {
		val declaration = type.get();
		return declaration instanceof ClassOrInterfaceDeclaration ? (ClassOrInterfaceDeclaration) declaration : null;
	}

	private String getPackageNameInternal() {
		return NodeUtil.qualifiedName(NodeUtil.getParentNode(type.get(), CompilationUnit.class).getPackageDeclaration().get().getName());
	}

	private ResolutionContext getContextInternal() {
		return ResolutionContext.of(type.get(), classPath);
	}

	@Override
	public String getName() {
		return className;
	}

	@Override
	public void setName(String name) {
		String packageName = getPackageName();
		if (name.startsWith(packageName)) {
			type.get().setName(name.replace(packageName, ""));
		} else {
			throw new TransformationException("Name '" + name + "' must be in package: " + packageName);
		}
	}

	@Override
	public AccessFlags getAccessFlags() {
		return new AccessFlags(type.get().getModifiers());
	}

	@Override
	public void setAccessFlags(AccessFlags accessFlags) {
		type.get().setModifiers(accessFlags.toJavaParserModifierSet());
	}

	@Override
	public String toString() {
		return "SourceInfo: " + getAccessFlags() + " " + getClassName();
	}

	@Override
	public void add(MethodInfo method) {
		BodyDeclaration<?> declaration;

		if (method instanceof MethodDeclarationWrapper) {
			val wrapper = (MethodDeclarationWrapper) method;
			val methodDeclaration = wrapper.declaration.clone();
			declaration = methodDeclaration;
			methodDeclaration.setAnnotations(NodeList.nodeList());
			changeTypeContext(wrapper.getContext(), getContext(), methodDeclaration);
		} else if (method.isConstructor()) {
			val constructorDeclaration = new ConstructorDeclaration();
			val name = getName();
			constructorDeclaration.setName(new SimpleName(name.substring(name.lastIndexOf('.') + 1)));
			declaration = constructorDeclaration;
			new ConstructorDeclarationWrapper(constructorDeclaration).setAll(method);
		} else {
			val methodDeclaration = new MethodDeclaration();
			declaration = methodDeclaration;
			new MethodDeclarationWrapper(methodDeclaration).setAll(method);
		}

		addMember(declaration);
	}

	@Override
	public void add(FieldInfo field) {
		FieldDeclaration fieldDeclaration;

		if (field instanceof FieldDeclarationWrapper) {
			val wrapper = (FieldDeclarationWrapper) field;
			fieldDeclaration = wrapper.declaration.clone();
			fieldDeclaration.setAnnotations(NodeList.nodeList());
			changeTypeContext(wrapper.getContext(), getContext(), fieldDeclaration);
		} else {
			fieldDeclaration = new FieldDeclaration();
			fieldDeclaration.setVariables(NodeList.nodeList(new VariableDeclarator()));
			new FieldDeclarationWrapper(fieldDeclaration).setAll(field);
		}

		addMember(fieldDeclaration);

		val result = new FieldDeclarationWrapper(fieldDeclaration);
		if (!field.similar(result))
			throw new TransformationException("After adding to class, didn't match. added: " + field + " result: " + result);
	}

	private void addMember(BodyDeclaration<?> bodyDeclaration) {
		bodyDeclaration.setParentNode(type.get());
		type.get().getMembers().add(bodyDeclaration);
	}

	@Override
	public void remove(MethodInfo method) {
		MethodDeclarationWrapper methodDeclarationWrapper = !(method instanceof MethodDeclarationWrapper) ? (MethodDeclarationWrapper) get(method) : (MethodDeclarationWrapper) method;

		if (methodDeclarationWrapper == null)
			throw new TransformationException("Method " + method + " can not be removed as it is not present");

		type.get().getMembers().remove(methodDeclarationWrapper.declaration);
	}

	@Override
	public void remove(FieldInfo field) {
		FieldDeclarationWrapper fieldDeclarationWrapper = !(field instanceof FieldDeclarationWrapper) ? (FieldDeclarationWrapper) get(field) : (FieldDeclarationWrapper) field;

		if (fieldDeclarationWrapper == null)
			throw new TransformationException("Field " + field + " can not be removed as it is not present");

		type.get().getMembers().remove(fieldDeclarationWrapper.declaration);
	}

	@Nullable
	@Override
	public Type getSuperType() {
		val declaration = getClassOrInterfaceDeclaration();
		if (declaration == null)
			return null;

		val extends_ = declaration.getExtendedTypes();

		if (extends_ == null || extends_.isEmpty())
			return null;

		return getContext().resolve(extends_.get(0));
	}

	@Override
	public List<Type> getInterfaceTypes() {
		val declaration = getClassOrInterfaceDeclaration();
		return declaration == null ? Collections.emptyList() : declaration.getImplementedTypes().stream().map(getContext()::resolve).collect(Collectors.toList());
	}

	public Stream<MethodInfo> getMethods() {
		return type.get().getMembers().stream()
			.map(this::getMethodInfoWrapper)
			.filter(Objects::nonNull);
	}

	private MethodInfo getMethodInfoWrapper(BodyDeclaration<?> x) {
		if (x instanceof MethodDeclaration)
			return new MethodDeclarationWrapper((MethodDeclaration) x);

		if (x instanceof ConstructorDeclaration)
			return new ConstructorDeclarationWrapper((ConstructorDeclaration) x);

		return null;
	}

	public Stream<FieldInfo> getFields() {
		return type.get().getMembers().stream()
			.filter(x -> x instanceof FieldDeclaration)
			.map(x -> new FieldDeclarationWrapper((FieldDeclaration) x));
	}

	private com.github.javaparser.ast.type.Type setType(Type newType, com.github.javaparser.ast.type.Type currentType) {
		val newType_ = ResolutionContext.typeToJavaParserType(newType);

		if (currentType instanceof ClassOrInterfaceType && newType_ instanceof ClassOrInterfaceType) {
			val annotations = currentType.getAnnotations();
			if (annotations != null && !annotations.isEmpty())
				newType_.setAnnotations(annotations);
		}
		return newType_;
	}

	private List<Annotation> getAnnotationsInternal() {
		return getAnnotationsInternal(type.get().getAnnotations());
	}

	private List<Annotation> getAnnotationsInternal(List<AnnotationExpr> l) {
		return l.stream().map((it) -> AnnotationParser.annotationFromAnnotationExpr(it, getContext())).collect(Collectors.toList());
	}

	@Override
	public ClassInfo getClassInfo() {
		return SourceInfo.this;
	}

	public class FieldDeclarationWrapper implements FieldInfo {
		private final FieldDeclaration declaration;
		private ResolutionContext context;

		FieldDeclarationWrapper(FieldDeclaration declaration) {
			this.declaration = declaration;
			if (declaration.getVariables().size() != 1) {
				throw new UnsupportedOperationException("Not yet implemented: multiple variables in one field decl.");
			}
		}

		private ResolutionContext getContext() {
			if (context != null)
				return context;
			return context = ResolutionContext.of(declaration, SourceInfo.this.type.get(), classPath);
		}

		@Override
		public String getName() {
			return declaration.getVariables().get(0).getName().asString();
		}

		@Override
		public void setName(String name) {
			declaration.getVariables().get(0).setName(name);
		}

		@Override
		public AccessFlags getAccessFlags() {
			return new AccessFlags(declaration.getModifiers());
		}

		@Override
		public void setAccessFlags(AccessFlags accessFlags) {
			declaration.setModifiers(accessFlags.toJavaParserModifierSet());
		}

		@Override
		public Type getType() {
			return getContext().resolve(declaration.getCommonType());
		}

		@Override
		public void setType(Type type) {
			declaration.getVariable(0).setType(SourceInfo.this.setType(type, declaration.getCommonType()));
		}

		@Override
		public List<Annotation> getAnnotations() {
			return SourceInfo.this.getAnnotationsInternal(declaration.getAnnotations());
		}

		@Override
		public SourceInfo getClassInfo() {
			return SourceInfo.this;
		}

		@Override
		public String toString() {
			return SimpleFieldInfo.toString(this);
		}

		@Override
		@SuppressWarnings("MethodDoesntCallSuperMethod")
		public FieldDeclarationWrapper clone() {
			return new FieldDeclarationWrapper(declaration.clone());
		}
	}

	public class MethodDeclarationWrapper implements MethodInfo {
		private final MethodDeclaration declaration;
		private ResolutionContext context;

		public MethodDeclarationWrapper(MethodDeclaration declaration) {
			this.declaration = declaration;
		}

		private ResolutionContext getContext() {
			if (context != null)
				return context;
			return context = ResolutionContext.of(declaration, SourceInfo.this.type.get(), classPath);
		}

		@Override
		public Type getReturnType() {
			return getContext().resolve(declaration.getType());
		}

		@Override
		public void setReturnType(Type type) {
			declaration.setType(setType(type, declaration.getType()));
		}

		@Override
		public List<Parameter> getParameters() {
			return SourceInfo.getParameters(declaration, this::getContext);
		}

		@Override
		public void setParameters(List<Parameter> parameters) {
			val javaParserParameters = parameters.stream().map(p -> new com.github.javaparser.ast.body.Parameter(ResolutionContext.typeToJavaParserType(p.type), p.name)).collect(Collectors.toList());
			declaration.setParameters(NodeList.nodeList(javaParserParameters));
		}

		@Override
		public String getName() {
			return declaration.getName().asString();
		}

		@Override
		public void setName(String name) {
			declaration.setName(name);
		}

		@Override
		public AccessFlags getAccessFlags() {
			return new AccessFlags(declaration.getModifiers());
		}

		@Override
		public void setAccessFlags(AccessFlags accessFlags) {
			declaration.setModifiers(accessFlags.toJavaParserModifierSet());
		}

		@Override
		public List<Annotation> getAnnotations() {
			return SourceInfo.this.getAnnotationsInternal(declaration.getAnnotations());
		}

		@Override
		public SourceInfo getClassInfo() {
			return SourceInfo.this;
		}

		@Override
		public String toString() {
			return SimpleMethodInfo.toString(this);
		}

		@Override
		public List<TypeVariable> getTypeVariables() {
			return declaration.getTypeParameters().stream().map(getContext()::resolveTypeVariable).collect(Collectors.toList());
		}

		@Override
		public void setTypeVariables(List<TypeVariable> typeVariables) {
			declaration.setTypeParameters(NodeList.nodeList(typeVariables.stream().map(getContext()::unresolveTypeVariable).collect(Collectors.toList())));
			context = null;
		}

		@Override
		@SuppressWarnings("MethodDoesntCallSuperMethod")
		public MethodDeclarationWrapper clone() {
			return new MethodDeclarationWrapper(declaration.clone());
		}
	}

	public class ConstructorDeclarationWrapper implements MethodInfo {
		private final ConstructorDeclaration declaration;
		private ResolutionContext context;

		public ConstructorDeclarationWrapper(ConstructorDeclaration declaration) {
			this.declaration = declaration;
		}

		private ResolutionContext getContext() {
			if (context != null)
				return context;
			return context = ResolutionContext.of(declaration, SourceInfo.this.type.get(), classPath);
		}

		@Override
		public Type getReturnType() {
			return getType();
		}

		@Override
		public void setReturnType(Type type) {
			if (!type.equals(getReturnType()))
				throw new UnsupportedOperationException("Can't setReturnType of constructor");
		}

		@Override
		public List<Parameter> getParameters() {
			return SourceInfo.getParameters(declaration, this::getContext);
		}

		@Override
		public void setParameters(List<Parameter> parameters) {
			val javaParserParameters = parameters.stream().map(p -> new com.github.javaparser.ast.body.Parameter(ResolutionContext.typeToJavaParserType(p.type), p.name)).collect(Collectors.toList());
			declaration.setParameters(NodeList.nodeList(javaParserParameters));
		}

		@Override
		public String getName() {
			return "<init>";
		}

		@Override
		public void setName(String name) {
			if (!name.equals("<init>"))
				throw new UnsupportedOperationException("Can't setName of constructor");
		}

		@Override
		public AccessFlags getAccessFlags() {
			return new AccessFlags(declaration.getModifiers());
		}

		@Override
		public void setAccessFlags(AccessFlags accessFlags) {
			declaration.setModifiers(accessFlags.toJavaParserModifierSet());
		}

		@Override
		public List<Annotation> getAnnotations() {
			return SourceInfo.this.getAnnotationsInternal(declaration.getAnnotations());
		}

		@Override
		public SourceInfo getClassInfo() {
			return SourceInfo.this;
		}

		@Override
		public String toString() {
			return SimpleMethodInfo.toString(this);
		}

		@Override
		public List<TypeVariable> getTypeVariables() {
			return Collections.emptyList();
		}

		@Override
		public void setTypeVariables(List<TypeVariable> typeVariables) {
			if (!typeVariables.isEmpty())
				throw new UnsupportedOperationException("Can't set type variables on a constructor");
		}

		@Override
		@SuppressWarnings("MethodDoesntCallSuperMethod")
		public ConstructorDeclarationWrapper clone() {
			return new ConstructorDeclarationWrapper(declaration.clone());
		}
	}
}
