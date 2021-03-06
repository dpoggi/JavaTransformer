package org.minimallycorrect.javatransformer.api;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import org.minimallycorrect.javatransformer.internal.util.CollectionUtil;

public interface ClassInfo extends ClassMember {
	default void add(ClassMember member) {
		if (member instanceof MethodInfo)
			add((MethodInfo) member);
		else if (member instanceof FieldInfo)
			add((FieldInfo) member);
		else
			throw new TransformationException("Can't add member of type " + member.getClass().getCanonicalName() + " to " + this);
	}

	void add(MethodInfo method);

	void add(FieldInfo field);

	default void remove(ClassMember member) {
		if (member instanceof MethodInfo)
			remove((MethodInfo) member);
		else if (member instanceof FieldInfo)
			remove((FieldInfo) member);
		else
			throw new TransformationException("Can't remove member of type " + member.getClass().getCanonicalName() + " to " + this);
	}

	void remove(MethodInfo method);

	void remove(FieldInfo field);

	@Nullable
	Type getSuperType();

	List<Type> getInterfaceTypes();

	@Nullable
	default ClassMember get(ClassMember member) {
		if (member instanceof MethodInfo)
			return get((MethodInfo) member);
		else if (member instanceof FieldInfo)
			return get((FieldInfo) member);
		else
			throw new TransformationException("Can't get member of type " + member.getClass().getCanonicalName() + " in " + this);
	}

	@Nullable
	default MethodInfo get(MethodInfo like) {
		for (MethodInfo methodInfo : CollectionUtil.iterable(getMethods())) {
			if (like.similar(methodInfo))
				return methodInfo;
		}

		return null;
	}

	@Nullable
	default FieldInfo get(FieldInfo like) {
		for (FieldInfo fieldInfo : CollectionUtil.iterable(getFields())) {
			if (like.similar(fieldInfo))
				return fieldInfo;
		}

		return null;
	}

	default Type getType() {
		return Type.of(getName());
	}

	Stream<MethodInfo> getMethods();

	Stream<FieldInfo> getFields();

	default Stream<MethodInfo> getConstructors() {
		return getMethods().filter(MethodInfo::isConstructor);
	}

	default Stream<ClassMember> getMembers() {
		return Stream.concat(getFields(), getMethods());
	}

	default void accessFlags(Function<AccessFlags, AccessFlags> c) {
		setAccessFlags(c.apply(getAccessFlags()));
	}
}
