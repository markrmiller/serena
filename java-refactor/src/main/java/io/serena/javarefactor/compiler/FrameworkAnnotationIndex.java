package io.serena.javarefactor.compiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiler-backed annotation fact source for the V3 framework SPI (refactor-feature-plan-V3.md §16). Walks the javac
 * element model of the whole project and yields every annotation application together with the program element it
 * decorates, the enclosing type's FQN, the annotation's FQN, its source range, and a rendering of its argument values.
 *
 * <p>Annotation FQNs are resolved through the {@code Element}/{@code AnnotationMirror} model (never textual guessing), so
 * a framework plugin can match its annotations by exact fully-qualified name. Argument values are rendered from the
 * mirror so callers can detect a target type named inside an annotation (e.g. {@code @JsonSubTypes(@Type(Foo.class))}).
 */
public final class FrameworkAnnotationIndex {

    /**
     * One annotation application.
     *
     * @param file            the source file
     * @param start           inclusive start offset of the decorated element's declaration
     * @param end             exclusive end offset of the decorated element's declaration
     * @param annotationFqn   fully-qualified name of the annotation type
     * @param enclosingTypeFqn fully-qualified name of the nearest enclosing type
     * @param elementKind     kind of the decorated element (CLASS, METHOD, FIELD, ...)
     * @param elementName     simple name of the decorated element
     * @param argumentText    rendered annotation argument values (for target-name matching)
     */
    public record AnnotationOccurrence(
            Path file,
            int start,
            int end,
            String annotationFqn,
            String enclosingTypeFqn,
            String elementKind,
            String elementName,
            String argumentText) {
    }

    private final Trees trees;
    private final SourcePositions positions;
    private final List<CompilationUnitTree> units;

    public FrameworkAnnotationIndex(SemanticIndex index) {
        this.trees = index.trees;
        this.positions = index.positions;
        this.units = index.units;
    }

    /** Every annotation application across the whole project, in compilation-unit order. */
    public List<AnnotationOccurrence> annotations() {
        List<AnnotationOccurrence> out = new ArrayList<>();
        for (CompilationUnitTree unit : units) {
            TreePath unitPath = new TreePath(unit);
            for (Tree decl : unit.getTypeDecls()) {
                Element element = trees.getElement(new TreePath(unitPath, decl));
                if (element instanceof TypeElement type) {
                    collect(type, unit, out);
                }
            }
        }
        return out;
    }

    private void collect(Element element, CompilationUnitTree unit, List<AnnotationOccurrence> out) {
        String enclosingTypeFqn = enclosingTypeFqn(element);
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String annotationFqn = annotationFqn(mirror);
            if (annotationFqn == null) {
                continue;
            }
            int[] range = rangeOf(element, unit);
            out.add(new AnnotationOccurrence(
                    SemanticIndex.pathOf(unit),
                    range[0],
                    range[1],
                    annotationFqn,
                    enclosingTypeFqn,
                    element.getKind().name(),
                    String.valueOf(element.getSimpleName()),
                    renderArguments(mirror)));
        }
        for (Element enclosed : element.getEnclosedElements()) {
            ElementKind kind = enclosed.getKind();
            if (kind.isClass() || kind.isInterface() || kind == ElementKind.METHOD
                    || kind == ElementKind.CONSTRUCTOR || kind == ElementKind.FIELD
                    || kind == ElementKind.ENUM_CONSTANT) {
                collect(enclosed, unit, out);
            }
        }
    }

    private int[] rangeOf(Element element, CompilationUnitTree unit) {
        Tree tree = trees.getTree(element);
        if (tree == null) {
            return new int[] {-1, -1};
        }
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        return new int[] {(int) start, (int) end};
    }

    private static String annotationFqn(AnnotationMirror mirror) {
        DeclaredType type = mirror.getAnnotationType();
        if (type == null) {
            return null;
        }
        Element annoElement = type.asElement();
        if (annoElement instanceof TypeElement typeElement) {
            return typeElement.getQualifiedName().toString();
        }
        return null;
    }

    private static String enclosingTypeFqn(Element element) {
        Element current = element;
        while (current != null) {
            if (current instanceof TypeElement typeElement) {
                return typeElement.getQualifiedName().toString();
            }
            current = current.getEnclosingElement();
        }
        return "";
    }

    private static String renderArguments(AnnotationMirror mirror) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<? extends Element, ? extends AnnotationValue> entry : mirror.getElementValues().entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey().getSimpleName()).append('=').append(renderValue(entry.getValue()));
        }
        return sb.toString();
    }

    private static String renderValue(AnnotationValue value) {
        Object raw = value.getValue();
        if (raw instanceof TypeMirror typeMirror) {
            return typeMirror.toString();
        }
        if (raw instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object element = list.get(i);
                sb.append(element instanceof AnnotationValue av ? renderValue(av) : String.valueOf(element));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(raw);
    }
}
