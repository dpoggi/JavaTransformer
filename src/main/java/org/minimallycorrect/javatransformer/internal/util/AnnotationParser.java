package org.minimallycorrect.javatransformer.internal.util;

import com.github.javaparser.ast.expr.*;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.minimallycorrect.javatransformer.api.Annotation;
import org.minimallycorrect.javatransformer.api.TransformationException;
import org.minimallycorrect.javatransformer.api.Type;
import org.minimallycorrect.javatransformer.internal.ResolutionContext;
import org.minimallycorrect.javatransformer.internal.SearchPath;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;

import java.util.*;
import java.util.stream.*;

@UtilityClass
public class AnnotationParser {
	public static List<Annotation> parseAnnotations(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		AnnotationVisitor cv = new AnnotationVisitor();
		cr.accept(cv, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		return cv.annotations.stream().map(AnnotationParser::annotationFromAnnotationNode).collect(Collectors.toList());
	}

	public static Annotation annotationFromAnnotationNode(AnnotationNode annotationNode) {
		return Annotation.of(new Type(annotationNode.desc), getAnnotationNodeValues(annotationNode));
	}

	private static Map<String, Object> getAnnotationNodeValues(AnnotationNode annotationNode) {
		if (annotationNode.values == null)
			return Collections.emptyMap();

		Map<String, Object> values = new HashMap<>();
		for (int i = 0; i < annotationNode.values.size(); i += 2) {
			values.put((String) annotationNode.values.get(i), annotationNode.values.get(i + 1));
		}
		return values;
	}

	public static Annotation annotationFromAnnotationExpr(AnnotationExpr annotationExpr, SearchPath path) {
		Type t = ResolutionContext.of(annotationExpr, path).resolve(NodeUtil.qualifiedName(annotationExpr.getName()));
		if (annotationExpr instanceof SingleMemberAnnotationExpr) {
			return Annotation.of(t, expressionToValue(((SingleMemberAnnotationExpr) annotationExpr).getMemberValue()));
		} else if (annotationExpr instanceof NormalAnnotationExpr) {
			val map = new HashMap<String, Object>();
			for (MemberValuePair memberValuePair : ((NormalAnnotationExpr) annotationExpr).getPairs()) {
				map.put(memberValuePair.getName().asString(), expressionToValue(memberValuePair.getValue()));
			}
			return Annotation.of(t, map);
		} else if (annotationExpr instanceof MarkerAnnotationExpr) {
			return Annotation.of(t);
		}
		throw new TransformationException("Unknown annotation type: " + annotationExpr.getClass().getCanonicalName());
	}

	private static Object expressionToValue(Expression e) {
		if (e instanceof StringLiteralExpr) {
			return ((StringLiteralExpr) e).getValue();
		} else if (e instanceof BooleanLiteralExpr) {
			return ((BooleanLiteralExpr) e).getValue();
		}
		throw new TransformationException("Unknown value: " + e);
	}

	private static class AnnotationVisitor extends ClassVisitor {
		public final List<AnnotationNode> annotations = new ArrayList<>();

		public AnnotationVisitor() {
			super(Opcodes.ASM5);
		}

		@Override
		public org.objectweb.asm.AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
			AnnotationNode an = new AnnotationNode(desc);
			annotations.add(an);
			return an;
		}
	}
}