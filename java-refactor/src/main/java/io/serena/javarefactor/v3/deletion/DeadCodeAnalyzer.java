package io.serena.javarefactor.v3.deletion;

import io.serena.javarefactor.compiler.ReachabilityGraph;
import io.serena.javarefactor.compiler.ReachabilityGraphCache;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.v3.frameworks.FrameworkParticipationCoordinator;
import io.serena.javarefactor.v3.frameworks.SymbolChange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * V3 compiler-backed dead-code scan (refactor-feature-plan-V3.md §7.5). Produces dead-code CANDIDATES only — it never
 * applies a deletion.
 *
 * <p>A candidate is a non-root declaration with no incoming semantic reference in the {@link ReachabilityGraph}.
 * Confidence is HIGH for a package-private/private symbol that nothing references, and LOW when the symbol has no Java
 * references but carries a framework/reflective annotation (e.g. {@code @RequestMapping}) or is registered as a
 * service-loader provider — either of which can make it an entry point invoked outside the Java type graph. Genuine
 * entry points (public/protected API under the default {@code keep} policy, {@code main}/native/serialization hooks, and
 * test symbols when tests are excluded) are not reported.
 */
public final class DeadCodeAnalyzer {

    /** Caller options mirroring the {@code java_find_dead_code} tool signature (§4.2). */
    public record Options(boolean includeTests, String publicApiPolicy, String scope) {
        public static Options defaults() {
            return new Options(false, "keep", "project");
        }

        /**
         * Returns the normalized public-API policy as one of {@code "keep"}, {@code "warn"}, or {@code "allow"}.
         *
         * <ul>
         *   <li>{@code keep}  — public/protected API symbols are never reported as dead-code candidates.</li>
         *   <li>{@code warn}  — public/protected API symbols that are otherwise unreachable ARE surfaced as candidates
         *       but carry a warning noting they cross the public-API boundary and need review.</li>
         *   <li>{@code allow} — public/protected API status is ignored; such symbols are treated like any internal
         *       symbol (deleted if unreachable, no special warning).</li>
         * </ul>
         *
         * The legacy value {@code "report"} is mapped to {@code "warn"} for backward compatibility. Any unrecognized
         * value defaults to {@code "keep"} (safest).
         */
        String effectivePublicApiPolicy() {
            if (publicApiPolicy == null) {
                return "keep";
            }
            return switch (publicApiPolicy.trim().toLowerCase()) {
                case "warn", "report" -> "warn";
                case "allow" -> "allow";
                default -> "keep";
            };
        }

        /**
         * The package prefix the scan is restricted to, or {@code null} for the whole project. {@code "project"} (the
         * default), an empty value, or {@code null} all mean "no restriction"; any other value is treated as a package
         * prefix (matched package-segment-aware against each candidate's owner-type FQN).
         */
        String scopePackage() {
            if (scope == null) {
                return null;
            }
            String trimmed = scope.trim();
            if (trimmed.isEmpty() || "project".equalsIgnoreCase(trimmed)) {
                return null;
            }
            return trimmed;
        }
    }

    public String analyze(JavaProjectModel model, Options options) throws IOException {
        String representative = firstJavaRelative(model);
        if (representative == null) {
            return PlannerSupport.refusalJson("no_sources",
                    "No Java source files are available for dead-code analysis.");
        }
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        Charset charset = SemanticIndex.charsetOf(model);
        ServiceLoaderScan serviceLoaderScan = serviceLoaderProviders(projectRoot, charset);
        Set<String> serviceProviders = serviceLoaderScan.providers();
        // R09 (refactor-feature-plan-V3.md §7.5 / §16): if an in-scope META-INF/services registration could not be read,
        // the provider set is INCOMPLETE — any unreferenced type might actually be a registered, reflectively-loaded
        // provider we failed to see. Erasing such a type into a high-confidence dead-code candidate would invite an
        // unsafe deletion, so an incomplete scan downgrades EVERY candidate to low confidence and surfaces the
        // incompleteness as a review warning (visible and safety-affecting), rather than silently trusting a partial scan.
        boolean serviceLoaderScanIncomplete = !serviceLoaderScan.complete();
        // Framework participation (refactor-feature-plan-V3.md §16): collect the reachability roots the framework
        // plugins contribute for a whole-project scan (e.g. JUnit test methods/classes, Spring @Bean methods and
        // request handlers). A symbol the framework owns as a root is reachable outside the Java type graph, so it is
        // never reported as a dead-code candidate.
        Set<String> frameworkRoots = new FrameworkParticipationCoordinator()
                .participate(model, SymbolChange.deadCodeScan()).roots();

        try (SemanticIndex index = SemanticIndex.open(model, representative)) {
            // Whole-project revision-keyed memoization (see ReachabilityGraphCache); a content edit to any source file
            // (touched or not) changes the key and forces a rebuild, so a stale graph can never be served.
            String projectKey = ReachabilityGraphCache.projectKey(model);
            boolean includeTests = options.includeTests();
            ReachabilityGraph graph = ReachabilityGraphCache.INSTANCE.get(projectKey, includeTests,
                    () -> ReachabilityGraph.build(index, model, includeTests));
            String policy = options.effectivePublicApiPolicy();

            // Per (ownerTypeFqn, kind, simpleName) overload sets: how many declarations share the name, and whether any
            // sibling is still referenced. Used to label a dead constructor/method that shadows a live sibling as an
            // "unused overload" (refactor-feature-plan-V3.md §7.1).
            Map<String, Integer> overloadCount = new java.util.HashMap<>();
            Map<String, Boolean> overloadHasLiveSibling = new java.util.HashMap<>();
            for (ReachabilityGraph.Node node : graph.nodes()) {
                if (node.kind() != ReachabilityGraph.NodeKind.METHOD
                        && node.kind() != ReachabilityGraph.NodeKind.CONSTRUCTOR) {
                    continue;
                }
                String overloadKey = overloadKey(node);
                overloadCount.merge(overloadKey, 1, Integer::sum);
                if (!graph.incoming(node.key()).isEmpty() || node.isCascadeRoot(true)) {
                    overloadHasLiveSibling.put(overloadKey, Boolean.TRUE);
                }
            }

            List<String> candidates = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            if (serviceLoaderScanIncomplete) {
                warnings.add(JsonUtil.quote("Service-loader provider scan was incomplete: one or more META-INF/services"
                        + " registration files could not be read. Dead-code candidates may include reflectively-loaded"
                        + " providers and must be reviewed before deletion."));
            }
            int high = 0;
            int low = 0;
            for (ReachabilityGraph.Node node : graph.nodes()) {
                if (!isCandidate(graph, node, policy, options.scopePackage())) {
                    continue;
                }
                if (isFrameworkRoot(node, frameworkRoots)) {
                    continue; // a framework plugin contributed this symbol (or its type) as a reachability root
                }
                boolean knownServiceProvider = node.kind() == ReachabilityGraph.NodeKind.TYPE
                        && serviceProviders.contains(node.ownerTypeFqn());
                boolean lowConfidence = serviceLoaderScanIncomplete
                        || node.frameworkEntry()
                        || knownServiceProvider;
                String confidence = lowConfidence ? "low" : "high";
                boolean unusedOverload = (node.kind() == ReachabilityGraph.NodeKind.METHOD
                                || node.kind() == ReachabilityGraph.NodeKind.CONSTRUCTOR)
                        && overloadCount.getOrDefault(overloadKey(node), 0) > 1
                        && overloadHasLiveSibling.getOrDefault(overloadKey(node), Boolean.FALSE);
                String reason = reason(node, lowConfidence, unusedOverload, serviceLoaderScanIncomplete,
                        knownServiceProvider);
                if (node.publicApi() && "warn".equals(policy)) {
                    String warningText = "Symbol '" + node.key()
                            + "' appears unreachable but crosses the public-API boundary"
                            + " — confirm it is not part of a published interface before deleting.";
                    warnings.add(JsonUtil.quote(warningText));
                }
                candidates.add("{\"symbol\":" + JsonUtil.quote(node.key())
                        + ",\"confidence\":" + JsonUtil.quote(confidence)
                        + ",\"reason\":" + JsonUtil.quote(reason) + "}");
                if (lowConfidence) {
                    low++;
                } else {
                    high++;
                }
            }

            return "{"
                    + "\"accepted\":true,"
                    + "\"operation\":\"findDeadCode\","
                    + "\"serviceLoaderScanIncomplete\":" + serviceLoaderScanIncomplete + ","
                    + "\"deadCodeCandidates\":[" + String.join(",", candidates) + "],"
                    + "\"warnings\":[" + String.join(",", warnings) + "],"
                    + "\"stats\":{\"candidates\":" + candidates.size()
                    + ",\"high\":" + high + ",\"low\":" + low + "}"
                    + "}";
        }
    }

    /**
     * Whether a framework plugin contributed {@code node} (or its enclosing type) as a reachability root through the
     * §16 participation hook. Roots are framework-FQNs ({@code com.acme.MyTest}) and member roots
     * ({@code com.acme.MyTest#runs}); a candidate matches when its owner type is a root (covering the type and every
     * member the framework owns) or its {@code ownerType#simpleName} is a member root.
     */
    private static boolean isFrameworkRoot(ReachabilityGraph.Node node, Set<String> frameworkRoots) {
        if (frameworkRoots.isEmpty()) {
            return false;
        }
        String ownerType = node.ownerTypeFqn();
        if (ownerType != null && frameworkRoots.contains(ownerType)) {
            return true;
        }
        return ownerType != null && frameworkRoots.contains(ownerType + "#" + node.simpleName());
    }

    private static boolean isCandidate(ReachabilityGraph graph, ReachabilityGraph.Node node, String policy,
            String scopePackage) {
        if (node.structuralRoot()) {
            return false; // main/native/serialization/test entry points are genuine roots
        }
        if (node.publicApi() && "keep".equals(policy)) {
            return false; // keep policy: public/protected API symbols are never reported as candidates
        }
        if (!withinScope(node.ownerTypeFqn(), scopePackage)) {
            return false; // candidate's owner type is outside the requested package scope
        }
        if (!graph.incoming(node.key()).isEmpty()) {
            return false; // referenced symbols are live
        }
        if (node.kind() == ReachabilityGraph.NodeKind.CONSTRUCTOR && enclosingTypeIsDeadCandidate(graph, node)) {
            // The whole owning type is itself an unreferenced candidate; reporting its constructor too is noise. A dead
            // constructor of a LIVE type (an unused/unused-overload constructor, §7.1) is still surfaced below.
            return false;
        }
        return true;
    }

    /**
     * Whether {@code node}'s enclosing type is itself an unreferenced dead-code candidate (no incoming references and not
     * a structural/framework root), so a member-level candidate inside it would be redundant noise.
     */
    private static boolean enclosingTypeIsDeadCandidate(ReachabilityGraph graph, ReachabilityGraph.Node node) {
        ReachabilityGraph.Node enclosing = graph.node(node.enclosingTypeKey());
        if (enclosing == null) {
            return false;
        }
        return !enclosing.structuralRoot() && !enclosing.frameworkEntry()
                && graph.incoming(enclosing.key()).isEmpty();
    }

    /** The overload-set key for a method/constructor: its owner type, kind and simple name. */
    private static String overloadKey(ReachabilityGraph.Node node) {
        return node.ownerTypeFqn() + "#" + node.kind() + "#" + node.simpleName();
    }

    /**
     * Whether an owner-type FQN falls within the requested package scope. A {@code null} scope (whole project) always
     * matches; otherwise the FQN must equal the scope or sit under it as a package-segment prefix ({@code com.acme.app}
     * matches {@code com.acme.app.Foo} and {@code com.acme.app.sub.Bar} but NOT {@code com.acme.application.Baz}).
     */
    private static boolean withinScope(String ownerTypeFqn, String scopePackage) {
        if (scopePackage == null) {
            return true;
        }
        if (ownerTypeFqn == null || ownerTypeFqn.isEmpty()) {
            return false;
        }
        return ownerTypeFqn.equals(scopePackage) || ownerTypeFqn.startsWith(scopePackage + ".");
    }

    private static String reason(ReachabilityGraph.Node node, boolean lowConfidence, boolean unusedOverload,
            boolean serviceLoaderScanIncomplete, boolean knownServiceProvider) {
        if (lowConfidence) {
            if (node.frameworkEntry()) {
                return "No Java references, but " + node.frameworkReason()
                        + " — may be invoked outside the Java type graph";
            }
            if (knownServiceProvider) {
                return "No Java references, but is registered as a service-loader provider — may be reflectively loaded";
            }
            // serviceLoaderScanIncomplete: the provider registry could not be fully read, so we cannot prove this symbol
            // is not a reflectively-loaded provider (R09, refactor-feature-plan-V3.md §7.5 / §16).
            return "No Java references, but the service-loader provider scan was incomplete"
                    + " (a META-INF/services registration could not be read) — review before deleting";
        }
        String visibility = node.publicApi() ? "public/protected " : node.privateMember() ? "private " : "package-private ";
        if (unusedOverload) {
            String label = node.kind() == ReachabilityGraph.NodeKind.CONSTRUCTOR ? "constructor overload" : "method overload";
            return visibility + "unused " + label + " with no incoming semantic references"
                    + " (another overload of '" + node.simpleName() + "' is still referenced)";
        }
        if (node.kind() == ReachabilityGraph.NodeKind.CONSTRUCTOR) {
            return visibility + "unused constructor with no incoming semantic references";
        }
        return visibility + node.kindLabel() + " with no incoming semantic references";
    }

    // ── service-loader discovery ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The outcome of scanning the project's {@code META-INF/services} tree: the providers found, plus whether the scan
     * was {@code complete}. An incomplete scan (a services file could not be walked or read) means we may have MISSED a
     * registration, so a symbol that looks unreferenced could still be a reflectively-loaded provider (R09).
     */
    private record ServiceLoaderScan(Set<String> providers, boolean complete) {}

    private static ServiceLoaderScan serviceLoaderProviders(Path projectRoot, Charset charset) {
        Set<String> providers = new LinkedHashSet<>();
        List<Path> serviceFiles;
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            serviceFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(DeadCodeAnalyzer::isServiceLoaderFile)
                    .toList();
        } catch (IOException | UncheckedIOException walkFailed) {
            // The services tree could not be enumerated; we cannot trust that we saw every provider registration.
            return new ServiceLoaderScan(providers, false);
        }
        boolean complete = true;
        for (Path serviceFile : serviceFiles) {
            String content;
            try {
                content = Files.readString(serviceFile, charset);
            } catch (IOException unreadable) {
                // A single registration file is unreadable — a provider it lists is now invisible to the analysis.
                complete = false;
                continue;
            }
            for (String line : content.split("\n", -1)) {
                int hash = line.indexOf('#');
                String provider = (hash < 0 ? line : line.substring(0, hash)).trim();
                if (!provider.isEmpty()) {
                    providers.add(provider);
                }
            }
        }
        return new ServiceLoaderScan(providers, complete);
    }

    private static boolean isServiceLoaderFile(Path path) {
        Path parent = path.getParent();
        Path grandParent = parent == null ? null : parent.getParent();
        return parent != null && grandParent != null
                && "services".equals(parent.getFileName().toString())
                && "META-INF".equals(grandParent.getFileName().toString());
    }

    private static String firstJavaRelative(JavaProjectModel model) {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path javaFile : sourceSet.javaFiles()) {
                Path absolute = javaFile.toAbsolutePath().normalize();
                if (absolute.startsWith(projectRoot)) {
                    return projectRoot.relativize(absolute).toString();
                }
            }
        }
        return null;
    }
}
