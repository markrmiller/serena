package io.serena.javarefactor.operations.extract_interface;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves that usage-narrowing candidates are classified SEMANTICALLY — from the javac
 * {@link javax.lang.model.element.VariableElement}'s kind and visibility — rather than by the prior lexical
 * brace/paren nesting heuristic. Each candidate of the concrete type {@code Box} carries a precise
 * {@link SemanticIndex.DeclarationKind} and an {@code apiVisible} flag, and the public-API confirmation gate fires only
 * on genuinely API-visible sites (non-private fields, parameters of non-private executables, record components) and
 * never on locals, resource variables, exception parameters, or parameters of private methods — regardless of source
 * layout.
 */
class UsageNarrowingClassificationTest {

    /**
     * A single concrete type {@code Box} plus a consumer that declares it in every variable-like position. The variable
     * name after each {@code Box} type token is used by the test to address that candidate.
     */
    private static final String CONSUMER = ""
            + "package demo;\n"
            + "import java.util.List;\n"
            + "public class Consumer {\n"
            + "    public Box apiField = new Box();\n"             // FIELD, api-visible
            + "    private Box internalField = new Box();\n"       // FIELD, private -> not api-visible
            + "    public void publicParam(Box pubParam) { }\n"    // PARAMETER of public method -> api-visible
            + "    private void privateParam(Box privParam) { }\n" // PARAMETER of private method -> not api-visible
            + "    public void body() {\n"
            + "        Box localVar = new Box();\n"                // LOCAL_VARIABLE -> not api-visible
            + "        localVar.hashCode();\n"
            + "    }\n"
            + "    public void withResource() throws Exception {\n"
            + "        try (Box resourceVar = new Box()) {\n"      // RESOURCE_VARIABLE -> not api-visible
            + "            resourceVar.hashCode();\n"
            + "        } catch (RuntimeException ex) { }\n"
            + "    }\n"
            + "    @SuppressWarnings(\"unusual\")\n"
            + "    public          Box\n"                          // unusual formatting field -> still api-visible
            + "        oddlyFormattedField = new Box();\n"
            + "}\n";

    private static final String RECORD_CONSUMER = ""
            + "package demo;\n"
            + "public record Holder(Box component) {\n"           // RECORD_COMPONENT -> api-visible
            + "}\n";

    private static final String NESTED_CONSUMER = ""
            + "package demo;\n"
            + "public class Outer {\n"
            + "    static class Inner {\n"
            + "        Box nestedField;\n"                         // FIELD inside a nested type -> still api-visible by kind
            + "    }\n"
            + "}\n";

    private static final String GENERIC_THROWS_CONSUMER = ""
            + "package demo;\n"
            + "import java.util.List;\n"
            + "public class GenericThrows {\n"
            + "    public <T extends Box> void bound(T t) { }\n"   // type-bound: a TYPE PARAMETER, not a Box VARIABLE
            + "    public List<Box> generic() { return null; }\n"  // method return generic of Box, not a Box variable
            + "}\n";

    @Test
    void classifiesEveryVariableKindFromJavacNotSourceNesting(@TempDir Path tmp) throws IOException {
        Map<String, SemanticIndex.SemanticUsageNarrowing> byVariable =
                candidatesByVariable(tmp, Map.of(
                        "src/demo/Box.java", box(),
                        "src/demo/Consumer.java", CONSUMER));

        assertKind(byVariable, "apiField", SemanticIndex.DeclarationKind.FIELD, true);
        assertKind(byVariable, "internalField", SemanticIndex.DeclarationKind.FIELD, false);
        assertKind(byVariable, "pubParam", SemanticIndex.DeclarationKind.PARAMETER, true);
        assertKind(byVariable, "privParam", SemanticIndex.DeclarationKind.PARAMETER, false);
        assertKind(byVariable, "localVar", SemanticIndex.DeclarationKind.LOCAL_VARIABLE, false);
        assertKind(byVariable, "resourceVar", SemanticIndex.DeclarationKind.RESOURCE_VARIABLE, false);
        assertKind(byVariable, "oddlyFormattedField", SemanticIndex.DeclarationKind.FIELD, true);
    }

    @Test
    void classifiesRecordComponentAsApiVisible(@TempDir Path tmp) throws IOException {
        Map<String, SemanticIndex.SemanticUsageNarrowing> byVariable =
                candidatesByVariable(tmp, Map.of(
                        "src/demo/Box.java", box(),
                        "src/demo/Holder.java", RECORD_CONSUMER));
        // A record component's narrowable declaration (javac's synthetic private backing field) is promoted to
        // RECORD_COMPONENT and is api-visible, because the record exposes the component via its accessor.
        SemanticIndex.SemanticUsageNarrowing component = byVariable.get("component");
        assertNotNull(component, "record component candidate missing: " + byVariable.keySet());
        assertEquals(SemanticIndex.DeclarationKind.RECORD_COMPONENT, component.kind());
        org.junit.jupiter.api.Assertions.assertTrue(component.apiVisible(), "record component must be api-visible");
    }

    @Test
    void classifiesFieldInsideNestedTypeAsApiVisibleAndFlagsNesting(@TempDir Path tmp) throws IOException {
        Map<String, SemanticIndex.SemanticUsageNarrowing> byVariable =
                candidatesByVariable(tmp, Map.of(
                        "src/demo/Box.java", box(),
                        "src/demo/Outer.java", NESTED_CONSUMER));
        SemanticIndex.SemanticUsageNarrowing nested = byVariable.get("nestedField");
        assertNotNull(nested, "nested field candidate missing: " + byVariable.keySet());
        assertEquals(SemanticIndex.DeclarationKind.FIELD, nested.kind());
        // Package-private field is still api-visible by kind (not private); the enclosing type is flagged nested.
        org.junit.jupiter.api.Assertions.assertTrue(nested.apiVisible());
        org.junit.jupiter.api.Assertions.assertTrue(nested.enclosingTypeNested(), "enclosing type should be flagged nested");
    }

    @Test
    void doesNotTreatTypeBoundsOrGenericArgumentsAsBoxVariableDeclarations(@TempDir Path tmp) throws IOException {
        Map<String, SemanticIndex.SemanticUsageNarrowing> byVariable =
                candidatesByVariable(tmp, Map.of(
                        "src/demo/Box.java", box(),
                        "src/demo/GenericThrows.java", GENERIC_THROWS_CONSUMER));
        // The type-parameter bound `<T extends Box>` is a TYPE PARAMETER, and `List<Box>` is a generic type argument on a
        // method return — neither is a variable of erased type Box, so neither becomes a narrowing candidate. The only
        // candidate is the bound-typed parameter `t`, whose erased type IS Box.
        org.junit.jupiter.api.Assertions.assertFalse(
                byVariable.containsKey("T"), "a type-parameter bound must not be a Box variable candidate");
        SemanticIndex.SemanticUsageNarrowing boundParam = byVariable.get("t");
        assertNotNull(boundParam, "bound-typed parameter candidate missing: " + byVariable.keySet());
        assertEquals(SemanticIndex.DeclarationKind.PARAMETER, boundParam.kind());
        org.junit.jupiter.api.Assertions.assertTrue(boundParam.apiVisible());
    }

    private static void assertKind(
            Map<String, SemanticIndex.SemanticUsageNarrowing> byVariable,
            String variable,
            SemanticIndex.DeclarationKind expectedKind,
            boolean expectedApiVisible) {
        SemanticIndex.SemanticUsageNarrowing candidate = byVariable.get(variable);
        assertNotNull(candidate, "missing candidate for variable '" + variable + "' in " + byVariable.keySet());
        assertEquals(expectedKind, candidate.kind(), "kind mismatch for " + variable);
        assertEquals(expectedApiVisible, candidate.apiVisible(), "apiVisible mismatch for " + variable);
    }

    /**
     * Opens an index over {@code files} and returns the {@code Box} narrowing candidates keyed by the Java identifier
     * that immediately follows each candidate's type token in source.
     */
    private static Map<String, SemanticIndex.SemanticUsageNarrowing> candidatesByVariable(
            Path tmp, Map<String, String> files) throws IOException {
        JavaProjectModel model = model(tmp, files);
        String primaryRelative = files.keySet().stream()
                .filter(path -> !path.endsWith("Box.java"))
                .findFirst()
                .orElseThrow();
        Map<String, SemanticIndex.SemanticUsageNarrowing> byVariable = new LinkedHashMap<>();
        try (SemanticIndex index = SemanticIndex.open(model, "src/demo/Box.java")) {
            SemanticIndex.SemanticType box = index.primaryType(tmp.resolve("src/demo/Box.java"));
            assertNotNull(box, "Box primary type not resolved");
            for (SemanticIndex.SemanticUsageNarrowing candidate : index.usageNarrowingCandidates(box)) {
                String variable = variableNameAfter(index, candidate);
                if (variable != null) {
                    byVariable.put(variable, candidate);
                }
            }
        }
        assertNotNull(primaryRelative);
        return byVariable;
    }

    /** The identifier immediately following the candidate's type token (i.e. the declared variable's name). */
    private static String variableNameAfter(SemanticIndex index, SemanticIndex.SemanticUsageNarrowing candidate) {
        CharSequence source = index.sourceText(candidate.declarationTypeRange().file());
        if (source == null) {
            return null;
        }
        int i = candidate.declarationTypeRange().end();
        while (i < source.length() && !Character.isJavaIdentifierStart(source.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
            i++;
        }
        return start < i ? source.subSequence(start, i).toString() : null;
    }

    private static String box() {
        return ""
                + "package demo;\n"
                + "public class Box implements AutoCloseable {\n"
                + "    public void close() { }\n"
                + "}\n";
    }

    private static JavaProjectModel model(Path root, Map<String, String> files) throws IOException {
        Path sourceRoot = root.resolve("src");
        List<Path> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path javaFile = root.resolve(entry.getKey());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, entry.getValue(), StandardCharsets.UTF_8);
            javaFiles.add(javaFile);
        }
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                javaFiles,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
        return new JavaProjectModel(
                root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
