package io.serena.javarefactor.v3.class_refactors;

import io.serena.javarefactor.project.JavaProjectModel;
import java.nio.file.Path;

/** Compatibility facade for the V3 public class-refactor package. */
public class ExtractClassPlanner extends io.serena.javarefactor.v3.classops.ExtractClassPlanner {
    public static final String IMPLEMENTATION_CLASS = "io.serena.javarefactor.v3.classops.ExtractClassPlanner";

    public ExtractClassPlanner(Path projectRoot, JavaProjectModel model) {
        super(projectRoot, model);
    }
}
