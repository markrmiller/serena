package io.serena.javarefactor.project;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import java.util.List;

/**
 * Normalized in-memory representation of the build-model.json schema produced by {@link BuildModelExtractor} for both
 * Gradle and Maven. Each module contributes one or more source sets; a source set carries its resolved compile
 * classpath, source/generated roots, and per-source-set compiler settings, which {@link ProjectModelDiscoverer} maps
 * onto {@link SourceSet} records.
 */
public record BuildModel(List<Module> modules) {
    public BuildModel {
        modules = List.copyOf(modules);
    }

    record Module(String project, List<ModelSourceSet> sourceSets) {
        Module {
            sourceSets = List.copyOf(sourceSets);
        }
    }

    record ModelSourceSet(
            String name,
            List<String> srcDirs,
            List<String> generatedRoots,
            List<String> outputDirs,
            List<String> classpath,
            List<String> modulePath,
            List<String> annotationProcessorPath,
            String release,
            String source,
            String target,
            String encoding,
            // Project/module identifiers this source set depends on within the reactor/multi-project build (Maven
            // reactor modules referenced as dependencies; Gradle `project(':x')` dependencies). The discoverer maps each
            // to the depended module's `main` source set so its source roots feed this set's -sourcepath, letting one
            // module resolve another's symbols from source even when that other module has not been compiled yet.
            List<String> dependsOnProjects,
            // The real, effective extra compiler arguments for this source set, captured verbatim from the build tool
            // (Gradle JavaCompile.options.compilerArgs; Maven compiler-plugin <compilerArgs>/<compilerArgument>, plus
            // synthesized flags for <parameters>, <enablePreview>, and -A processor options). These are merged into the
            // source set's javacOptions so flags like --enable-preview, --add-exports, --add-opens, -parameters, and
            // annotation-processor options reach javac exactly as the build would pass them.
            List<String> compilerArgs,
            // G003 build-model completeness: false when this source set's dependency classpath could NOT be proven during
            // extraction (Maven dependency:build-classpath exited non-zero for the scope, or the module declares external
            // dependencies in the effective POM but produced a missing/empty classpath file). An unproven classpath can
            // leave javac "clean" on the edited file while corrupting semantic planning (overload resolution, type
            // hierarchy) elsewhere, so the discoverer promotes this to a first-class model-incompleteness signal that
            // refuses apply (independent of javac diagnostics). Gradle and EXPLICIT/conventional source sets are always
            // proven (Gradle hard-fails extraction on a non-zero exit; conventional fallback refuses apply separately).
            boolean classpathProven,
            // B11 model-first resource roots: the resource directories the build model declared for this source set,
            // read DIRECTLY from the build tool (Gradle {@code sourceSets.*.resources.srcDirs} emitted as the init
            // script's {@code resourceDirs} JSON field; Maven {@code <build><resources>}/{@code <testResources>}
            // {@code <directory>} entries). Kept SEPARATE from {@code srcDirs} (which carries java/generated roots) so a
            // non-conventional resource directory survives to {@link SourceSet#resourceRoots()} and is discovered by
            // {@link ResourceRootModel} model-first. Empty for legacy/explicit payloads that declare no resource dirs.
            List<String> resourceDirs
    ) {
        // Backward-compatible constructor: callers that pre-date the G003 classpathProven signal (and every source set
        // whose classpath IS proven — Gradle, explicit models) construct with classpathProven=true.
        ModelSourceSet(String name, List<String> srcDirs, List<String> generatedRoots, List<String> outputDirs,
                List<String> classpath, List<String> modulePath, List<String> annotationProcessorPath, String release,
                String source, String target, String encoding, List<String> dependsOnProjects, List<String> compilerArgs) {
            this(name, srcDirs, generatedRoots, outputDirs, classpath, modulePath, annotationProcessorPath, release,
                    source, target, encoding, dependsOnProjects, compilerArgs, true, List.of());
        }

        // Backward-compatible constructor for callers that supply classpathProven (G003) but pre-date the B11
        // resourceDirs field: resource dirs default to empty.
        ModelSourceSet(String name, List<String> srcDirs, List<String> generatedRoots, List<String> outputDirs,
                List<String> classpath, List<String> modulePath, List<String> annotationProcessorPath, String release,
                String source, String target, String encoding, List<String> dependsOnProjects, List<String> compilerArgs,
                boolean classpathProven) {
            this(name, srcDirs, generatedRoots, outputDirs, classpath, modulePath, annotationProcessorPath, release,
                    source, target, encoding, dependsOnProjects, compilerArgs, classpathProven, List.of());
        }

        ModelSourceSet {
            srcDirs = List.copyOf(srcDirs);
            generatedRoots = List.copyOf(generatedRoots);
            outputDirs = List.copyOf(outputDirs);
            classpath = List.copyOf(classpath);
            modulePath = List.copyOf(modulePath);
            annotationProcessorPath = List.copyOf(annotationProcessorPath);
            dependsOnProjects = List.copyOf(dependsOnProjects);
            compilerArgs = List.copyOf(compilerArgs);
            resourceDirs = List.copyOf(resourceDirs);
        }
    }
}
