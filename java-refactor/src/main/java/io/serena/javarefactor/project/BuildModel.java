package io.serena.javarefactor.project;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

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
            List<String> compilerArgs
    ) {
        ModelSourceSet {
            srcDirs = List.copyOf(srcDirs);
            generatedRoots = List.copyOf(generatedRoots);
            outputDirs = List.copyOf(outputDirs);
            classpath = List.copyOf(classpath);
            modulePath = List.copyOf(modulePath);
            annotationProcessorPath = List.copyOf(annotationProcessorPath);
            dependsOnProjects = List.copyOf(dependsOnProjects);
            compilerArgs = List.copyOf(compilerArgs);
        }
    }
}
