package io.serena.javarefactor.ast;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Canonical, cross-compiler-task-stable key for a refactorable element.
 *
 * <p>The {@link #canonical()} string follows the schema in the feature plan (section 6):
 * <pre>
 * TYPE       com.acme.Foo
 * FIELD      com.acme.Foo#count
 * METHOD     com.acme.Foo#bar(java.lang.String,int)
 * CTOR       com.acme.Foo#&lt;init&gt;(java.lang.String)
 * LOCAL      declFile@declOffset#simpleName
 * PARAMETER  &lt;enclosingExecutableCanonical&gt;@declOffset#simpleName
 * PACKAGE    com.acme
 * </pre>
 *
 * <p>Canonical keys are derived only from names and declaration file/offset (never from
 * {@link Element} identity/hashCode), so the same logical element compiled in two different
 * compiler tasks produces the same canonical key.
 *
 * <p>The {@code declFile}/{@code declOffset} components are populated only for declaration-location
 * kinds (locals, resources, exception parameters, and method parameters); for all other kinds
 * {@code declFile} is {@code null} and {@code declOffset} is {@code -1}.
 */
public record SemanticKey(String kind, String owner, String name, String signature, String canonical, String declFile, long declOffset) {

    public static SemanticKey from(Element element) {
        return from(element, null, null, null, null);
    }

    public static SemanticKey from(Element element, Trees trees, Types types, CompilationUnitTree unit, Path file) {
        String kind = element.getKind().name();
        String owner = ownerName(element);
        String name = element instanceof TypeElement typeElement
                ? typeElement.getQualifiedName().toString()
                : element.getSimpleName().toString();
        String signature = element instanceof ExecutableElement executableElement
                ? executableElement.getParameters().stream().map(parameter -> parameter.asType().toString()).collect(Collectors.joining(",", "(", ")"))
                : element.asType().toString();

        String declFile = null;
        long declOffset = -1;
        if (isDeclarationLocation(element.getKind()) && trees != null && unit != null && file != null) {
            TreePath path = trees.getPath(element);
            if (path != null) {
                Tree tree = path.getLeaf();
                long start = trees.getSourcePositions().getStartPosition(unit, tree);
                if (start >= 0) {
                    declOffset = start;
                    declFile = file.toAbsolutePath().normalize().toString().replace('\\', '/');
                }
            }
        }

        String canonical = canonical(element, kind, owner, name, declFile, declOffset, types);
        return new SemanticKey(kind, owner, name, signature, canonical, declFile, declOffset);
    }

    String toJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{")
                .append("\"kind\":").append(JsonUtil.quote(kind)).append(",")
                .append("\"owner\":").append(JsonUtil.quote(owner)).append(",")
                .append("\"name\":").append(JsonUtil.quote(name)).append(",")
                .append("\"signature\":").append(JsonUtil.quote(signature)).append(",")
                .append("\"canonical\":").append(JsonUtil.quote(canonical));
        if (declFile != null) {
            builder.append(",\"declFile\":").append(JsonUtil.quote(declFile))
                    .append(",\"declOffset\":").append(declOffset);
        }
        builder.append("}");
        return builder.toString();
    }

    private static boolean isDeclarationLocation(ElementKind kind) {
        return switch (kind) {
            case LOCAL_VARIABLE, RESOURCE_VARIABLE, EXCEPTION_PARAMETER, PARAMETER -> true;
            default -> kind.name().equals("BINDING_VARIABLE");
        };
    }

    private static String canonical(
            Element element,
            String kind,
            String owner,
            String name,
            String declFile,
            long declOffset,
            Types types) {
        ElementKind elementKind = element.getKind();
        switch (elementKind) {
            case PACKAGE:
                return name;
            case CLASS:
            case INTERFACE:
            case ENUM:
            case RECORD:
            case ANNOTATION_TYPE:
                return element instanceof TypeElement typeElement ? typeElement.getQualifiedName().toString() : name;
            case CONSTRUCTOR:
                return owner + "#<init>" + erasedSignature(element, types);
            case METHOD:
                return owner + "#" + name + erasedSignature(element, types);
            case PARAMETER:
                return enclosingExecutableCanonical(element, types) + "@" + declOffset + "#" + name;
            case LOCAL_VARIABLE:
            case RESOURCE_VARIABLE:
            case EXCEPTION_PARAMETER:
                return (declFile == null ? "" : declFile) + "@" + declOffset + "#" + name;
            default:
                if (elementKind.isField()) {
                    return owner + "#" + name;
                }
                if (elementKind.name().equals("BINDING_VARIABLE")) {
                    return (declFile == null ? "" : declFile) + "@" + declOffset + "#" + name;
                }
                return kind + ":" + owner + "#" + name;
        }
    }

    private static String erasedSignature(Element element, Types types) {
        if (!(element instanceof ExecutableElement executableElement)) {
            return "()";
        }
        return executableElement.getParameters().stream()
                .map(parameter -> erasedTypeName(parameter.asType(), types))
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static String erasedTypeName(TypeMirror type, Types types) {
        return types == null ? type.toString() : types.erasure(type).toString();
    }

    private static String enclosingExecutableCanonical(Element element, Types types) {
        Element enclosing = element.getEnclosingElement();
        if (enclosing instanceof ExecutableElement executable) {
            String owner = ownerName(executable);
            if (executable.getKind() == ElementKind.CONSTRUCTOR) {
                return owner + "#<init>" + erasedSignature(executable, types);
            }
            return owner + "#" + executable.getSimpleName().toString() + erasedSignature(executable, types);
        }
        return ownerName(element) + "#" + (enclosing == null ? "" : enclosing.getSimpleName().toString());
    }

    private static String ownerName(Element element) {
        Element enclosing = element.getEnclosingElement();
        while (enclosing != null) {
            if (enclosing instanceof TypeElement typeElement) {
                return typeElement.getQualifiedName().toString();
            }
            enclosing = enclosing.getEnclosingElement();
        }
        return "";
    }
}
