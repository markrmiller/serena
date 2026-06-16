package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.project.JavaProjectModel;
import java.nio.file.Path;
import java.util.Map;

/**
 * Conservative V2 hierarchy-member planner facade for direct source-backed pull-up and push-down moves.
 *
 * <p>G009: the monolithic implementation has been decomposed (plan §3) into focused, independently tested units that
 * share {@link HierarchyMoveSupport}:
 * <ul>
 *   <li>{@link PullUpPlanner} — the pull-up orchestration,</li>
 *   <li>{@link PushDownPlanner} — the push-down orchestration,</li>
 *   <li>{@link OverrideGroupResolver} — the semantic override/sibling-compatibility resolver invoked before edits.</li>
 * </ul>
 * This facade preserves the original public entry points ({@link #pullUpMember} / {@link #pushDownMember}) so the
 * protocol layer is unaffected, delegating each call to the matching unit.
 */
public final class PullPushMemberPlanner {
    private final Path projectRoot;
    private final JavaProjectModel model;

    public PullPushMemberPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot;
        this.model = model;
    }

    public String pullUpMember(Map<String, Object> fields, boolean apply) {
        return new PullUpPlanner(projectRoot, model).pullUpMember(fields, apply);
    }

    public String pushDownMember(Map<String, Object> fields, boolean apply) {
        return new PushDownPlanner(projectRoot, model).pushDownMember(fields, apply);
    }
}
