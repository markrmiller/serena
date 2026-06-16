package io.serena.javarefactor.planners;

import java.util.Map;

/** A V2 planner entry point that receives shared semantic infrastructure instead of duplicating analyses. */
public interface RefactorPlanner {
    String operation();

    Map<String, Object> plan(PlanningContext context, Map<String, Object> params);
}
