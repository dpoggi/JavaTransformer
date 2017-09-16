package org.minimallycorrect.javatransformer.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.val;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

import org.minimallycorrect.javatransformer.api.Type;
import org.minimallycorrect.javatransformer.api.code.CodeFragment;
import org.minimallycorrect.javatransformer.api.code.IntermediateValue;
import org.minimallycorrect.javatransformer.internal.javaparser.Expressions;
import org.minimallycorrect.javatransformer.internal.util.NodeUtil;

public class JavaParserCodeFragmentGenerator {
	static Class<?> concreteImplementation(Class<?> interfaceType) {
		if (interfaceType == CodeFragment.Body.class)
			return CallableDeclarationCodeFragment.class;
		if (interfaceType == CodeFragment.MethodCall.class)
			return MethodCall.class;
		throw new UnsupportedOperationException("No ASM implementation for " + interfaceType);
	}

	public abstract static class JavaParserCodeFragment implements CodeFragment {
		final SourceInfo.CallableDeclarationWrapper<?> containingWrapper;

		public JavaParserCodeFragment(SourceInfo.CallableDeclarationWrapper<?> containingWrapper) {
			this.containingWrapper = containingWrapper;
		}

		@Override
		public ExecutionOutcome getExecutionOutcome() {
			// TODO: implement this?
			return new ExecutionOutcome(true, true, true);
		}

		@Override
		public void insert(@NonNull CodeFragment codeFragment, @NonNull InsertionPosition position, @NonNull InsertionOptions insertionOptions) {
			throw new UnsupportedOperationException("Not yet implemented");
		}

		private BlockStmt getContainingBody() {
			return Objects.requireNonNull(containingWrapper.getBody());
		}

		@Override
		@SuppressWarnings({"JavaReflectionMemberAccess", "unchecked"})
		@SneakyThrows
		public <T extends CodeFragment> List<T> findFragments(Class<T> fragmentType) {
			if (fragmentType.isInstance(this))
				return Collections.singletonList((T) this);

			val constructor = (Constructor<T>) concreteImplementation(fragmentType).getDeclaredConstructors()[0];
			val list = new ArrayList<T>();
			NodeUtil.forChildren(getContainingBody(), it -> {
				try {
					list.add(constructor.newInstance(containingWrapper, it));
				} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
					throw new RuntimeException(e);
				}
			}, constructor.getParameterTypes()[1]);
			return list;
		}
	}

	public static class CallableDeclarationCodeFragment extends JavaParserCodeFragment implements CodeFragment.Body {
		public CallableDeclarationCodeFragment(SourceInfo.CallableDeclarationWrapper<?> containingWrapper) {
			super(containingWrapper);
		}

		@NonNull
		@Override
		public List<IntermediateValue> getInputTypes() {
			return containingWrapper.getParameters().stream().map(it -> new IntermediateValue(it.type, IntermediateValue.UNKNOWN, new IntermediateValue.Location(IntermediateValue.LocationType.LOCAL, -1, it.name))).collect(Collectors.toList());
		}

		@NonNull
		@Override
		public List<IntermediateValue> getOutputTypes() {
			val ret = containingWrapper.getReturnType();
			if (ret.getDescriptorType() == Type.DescriptorType.VOID)
				return Collections.emptyList();

			return Collections.singletonList(new IntermediateValue(ret, IntermediateValue.UNKNOWN, new IntermediateValue.Location(IntermediateValue.LocationType.LOCAL, -1, "return;")));
		}
	}

	public static class MethodCall extends JavaParserCodeFragment implements CodeFragment.MethodCall {
		final MethodCallExpr expr;

		public MethodCall(SourceInfo.CallableDeclarationWrapper<?> containingWrapper, MethodCallExpr methodCallExpr) {
			super(containingWrapper);
			this.expr = methodCallExpr;
		}

		@NonNull
		@Override
		public List<IntermediateValue> getInputTypes() {
			return expr.getArguments().stream().map(it -> {
				Type type = Expressions.expressionToType(it, containingWrapper.getContext(), true);
				return new IntermediateValue(type, IntermediateValue.UNKNOWN, new IntermediateValue.Location(IntermediateValue.LocationType.STACK, -1, "argument"));
			}).collect(Collectors.toList());
		}

		@NonNull
		@Override
		public List<IntermediateValue> getOutputTypes() {
			return Collections.emptyList();
		}

		@NonNull
		@Override
		public Type getContainingClassType() {
			val containingScope = expr.getScope().orElse(null);
			String scope = containingScope == null ? null : containingScope.toString();
			return containingWrapper.getContext().resolve(scope);
		}

		@NonNull
		@Override
		public String getName() {
			return expr.getNameAsString();
		}
	}
}
