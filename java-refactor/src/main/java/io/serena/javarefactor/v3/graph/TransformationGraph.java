package io.serena.javarefactor.v3.graph;

import io.serena.javarefactor.protocol.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The unified, whole-project transformation graph for one project revision (refactor-feature-plan-V3.md §1.2): the seven
 * components every V3 impact/delete/report consumer reasons over.
 *
 * <p>It is assembled once per revision by {@link TransformationGraphBuilder} from the real compiler model, the validated
 * build model, the resource SPI, and the framework index — never from stubs — and cached by {@link GraphInvalidation}
 * keyed on the content-addressed project revision. Because every field is a materialized value object (no live javac
 * {@code Element}/{@code TypeMirror} handles), a {@code TransformationGraph} stays valid after the {@link
 * io.serena.javarefactor.compiler.SemanticIndex} it was built from is closed and can be safely held in the cache.
 *
 * @param project   project identity + revision
 * @param symbols   types/members + package&rarr;root and type&rarr;file maps
 * @param hierarchy supertype/subtype closure + override groups
 * @param calls     resolved caller&rarr;callee edges
 * @param resources provider-backed resource references
 * @param build     build-layout source roots
 * @param tests     test types and the production types they exercise
 */
public record TransformationGraph(
        ProjectGraph project,
        JavaSymbolGraph symbols,
        TypeHierarchyIndex hierarchy,
        CallGraph calls,
        ResourceReferenceGraph resources,
        BuildGraph build,
        TestGraph tests) {

    /**
     * The authoritative JSON projection of the graph for the {@code graph.build} op. Mirrors the field shape the Python
     * graph-report contract ({@code serena.java_refactor_v3.graph.models}) consumes, so the Python side performs a pure
     * mechanical reshape with zero inference.
     */
    public String toJson() {
        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"graph.build\","
                + "\"project\":" + projectJson() + ","
                + "\"build\":" + buildJson() + ","
                + "\"symbols\":" + symbolsJson() + ","
                + "\"hierarchy\":" + hierarchyJson() + ","
                + "\"calls\":" + callsJson() + ","
                + "\"resources\":" + resourcesJson() + ","
                + "\"tests\":" + testsJson() + ","
                + "\"stats\":" + statsJson()
                + "}";
    }

    private String projectJson() {
        return "{"
                + "\"projectRoot\":" + JsonUtil.quote(project.projectRoot()) + ","
                + "\"revision\":" + JsonUtil.quote(project.revision()) + ","
                + "\"buildSystem\":" + JsonUtil.quote(project.buildSystem()) + ","
                + "\"moduleIds\":" + JsonUtil.array(project.moduleIds())
                + "}";
    }

    private String buildJson() {
        List<String> moduleJsons = new ArrayList<>();
        for (BuildGraph.ModuleNode module : build.modules()) {
            List<String> rootJsons = new ArrayList<>();
            for (BuildGraph.SourceRoot root : module.sourceRoots()) {
                rootJsons.add("{"
                        + "\"path\":" + JsonUtil.quote(root.relativePath()) + ","
                        + "\"kind\":" + JsonUtil.quote(root.kind().name().toLowerCase()) + ","
                        + "\"content\":" + JsonUtil.quote(root.content().name().toLowerCase()) + ","
                        + "\"module\":" + JsonUtil.quote(root.moduleId())
                        + "}");
            }
            moduleJsons.add("{"
                    + "\"id\":" + JsonUtil.quote(module.moduleId()) + ","
                    + "\"buildSystem\":" + JsonUtil.quote(module.buildSystem()) + ","
                    + "\"sourceRoots\":" + JsonUtil.rawArray(rootJsons)
                    + "}");
        }
        return "{"
                + "\"buildSystem\":" + JsonUtil.quote(build.buildSystem()) + ","
                + "\"modules\":" + JsonUtil.rawArray(moduleJsons)
                + "}";
    }

    private String symbolsJson() {
        List<String> typeJsons = new ArrayList<>();
        for (JavaSymbolGraph.TypeNode type : symbols.typesByFqn().values()) {
            typeJsons.add("{"
                    + "\"fqn\":" + JsonUtil.quote(type.fqn()) + ","
                    + "\"simpleName\":" + JsonUtil.quote(type.simpleName()) + ","
                    + "\"package\":" + JsonUtil.quote(type.packageName()) + ","
                    + "\"kind\":" + JsonUtil.quote(type.kind()) + ","
                    + "\"path\":" + JsonUtil.quote(type.relativePath()) + ","
                    + "\"topLevel\":" + type.topLevel() + ","
                    + "\"publicApi\":" + type.publicApi() + ","
                    + "\"testSource\":" + type.testSource()
                    + "}");
        }
        List<String> memberJsons = new ArrayList<>();
        for (JavaSymbolGraph.MemberNode member : symbols.members()) {
            memberJsons.add("{"
                    + "\"key\":" + JsonUtil.quote(member.key()) + ","
                    + "\"owner\":" + JsonUtil.quote(member.ownerFqn()) + ","
                    + "\"name\":" + JsonUtil.quote(member.name()) + ","
                    + "\"memberKind\":" + JsonUtil.quote(member.memberKind()) + ","
                    + "\"arity\":" + member.arity() + ","
                    + "\"path\":" + JsonUtil.quote(member.relativePath()) + ","
                    + "\"publicApi\":" + member.publicApi() + ","
                    + "\"testSource\":" + member.testSource()
                    + "}");
        }
        return "{"
                + "\"types\":" + JsonUtil.rawArray(typeJsons) + ","
                + "\"members\":" + JsonUtil.rawArray(memberJsons) + ","
                + "\"packageToSourceRoots\":" + mapOfSetsJson(symbols.packageToSourceRoots()) + ","
                + "\"typeToFile\":" + mapOfStringsJson(symbols.typeToFile()) + ","
                + "\"filesByPackage\":" + mapOfSetsJson(symbols.filesByPackage()) + ","
                + "\"publicApiFqns\":" + JsonUtil.array(sorted(symbols.publicApiFqns()))
                + "}";
    }

    private String hierarchyJson() {
        return "{"
                + "\"supertypes\":" + mapOfSetsJson(hierarchy.supertypes()) + ","
                + "\"subtypes\":" + mapOfSetsJson(hierarchy.subtypes()) + ","
                + "\"overrideGroups\":" + mapOfSetsJson(hierarchy.overrideGroups())
                + "}";
    }

    private String callsJson() {
        return "{"
                + "\"memberCount\":" + calls.memberCount() + ","
                + "\"resolved\":" + calls.resolved() + ","
                + "\"callEdges\":" + mapOfSetsJson(calls.callEdges()) + ","
                + "\"constructorEdges\":" + mapOfSetsJson(calls.constructorEdges()) + ","
                + "\"methodReferenceEdges\":" + mapOfSetsJson(calls.methodReferenceEdges())
                + "}";
    }

    private String resourcesJson() {
        List<String> refJsons = new ArrayList<>();
        for (ResourceReferenceGraph.Reference ref : resources.references()) {
            refJsons.add("{"
                    + "\"target\":" + JsonUtil.quote(ref.target()) + ","
                    + "\"path\":" + JsonUtil.quote(ref.relativePath()) + ","
                    + "\"startOffset\":" + ref.startOffset() + ","
                    + "\"endOffset\":" + ref.endOffset() + ","
                    + "\"oldText\":" + JsonUtil.quote(ref.oldText()) + ","
                    + "\"kind\":" + JsonUtil.quote(ref.kind()) + ","
                    + "\"confidence\":" + JsonUtil.quote(ref.confidence()) + ","
                    + "\"provider\":" + JsonUtil.quote(ref.providerId())
                    + "}");
        }
        // Story R06: carry the resource-scan completeness gate into the emitted graph so a consumer can detect that the
        // resource view is partial (an in-scope file was unreadable / over-cap) and escalate risk / refuse auto-apply.
        return "{\"references\":" + JsonUtil.rawArray(refJsons) + ","
                + "\"scanIncomplete\":" + resources.scanIncomplete() + ","
                + "\"incompleteResourceFiles\":" + JsonUtil.array(resources.incompleteResourceFiles())
                + "}";
    }

    private String testsJson() {
        List<String> testJsons = new ArrayList<>();
        for (TestGraph.TestNode test : tests.tests()) {
            testJsons.add("{"
                    + "\"testFqn\":" + JsonUtil.quote(test.testFqn()) + ","
                    + "\"path\":" + JsonUtil.quote(test.relativePath()) + ","
                    + "\"references\":" + JsonUtil.array(sorted(test.referencedTypes()))
                    + "}");
        }
        return "{\"tests\":" + JsonUtil.rawArray(testJsons) + "}";
    }

    private String statsJson() {
        return "{"
                + "\"types\":" + symbols.typesByFqn().size() + ","
                + "\"members\":" + symbols.members().size() + ","
                + "\"hierarchyTypes\":" + hierarchy.supertypes().size() + ","
                + "\"callMembers\":" + calls.memberCount() + ","
                + "\"resourceRefs\":" + resources.references().size() + ","
                + "\"resourceScanIncomplete\":" + resources.scanIncomplete() + ","
                + "\"tests\":" + tests.tests().size() + ","
                + "\"modules\":" + build.modules().size()
                + "}";
    }

    // ── JSON helpers ────────────────────────────────────────────────────────────────────────────────

    private static String mapOfSetsJson(Map<String, Set<String>> map) {
        Map<String, String> rendered = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
            rendered.put(entry.getKey(), JsonUtil.array(sorted(entry.getValue())));
        }
        return rawObject(rendered);
    }

    private static String mapOfStringsJson(Map<String, String> map) {
        Map<String, String> rendered = new TreeMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            rendered.put(entry.getKey(), JsonUtil.quote(entry.getValue()));
        }
        return rawObject(rendered);
    }

    /** Renders a {@code String -> already-serialized-JSON} map as a JSON object (values inserted verbatim). */
    private static String rawObject(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(JsonUtil.quote(entry.getKey())).append(":").append(entry.getValue());
        }
        return sb.append("}").toString();
    }

    private static List<String> sorted(Set<String> values) {
        List<String> list = new ArrayList<>(values);
        list.sort(String::compareTo);
        return list;
    }
}
