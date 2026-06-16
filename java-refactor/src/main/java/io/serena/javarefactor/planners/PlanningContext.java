package io.serena.javarefactor.planners;

import io.serena.javarefactor.operations.hierarchy.TypeHierarchyIndex;
import io.serena.javarefactor.shared.AccessPlanner;
import io.serena.javarefactor.shared.ExpressionPurityAnalyzer;
import io.serena.javarefactor.shared.ImportManager;

/** Shared semantic services supplied to each V2 refactor planner. */
public record PlanningContext(
        TypeHierarchyIndex hierarchyIndex,
        ExpressionPurityAnalyzer purityAnalyzer,
        AccessPlanner accessPlanner,
        ImportManager importManager) {}
