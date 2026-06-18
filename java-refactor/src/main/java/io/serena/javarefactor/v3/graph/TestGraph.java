package io.serena.javarefactor.v3.graph;

import java.util.List;
import java.util.Set;

/**
 * The test view (refactor-feature-plan-V3.md §1.2): the project's test types and the production types each one exercises.
 *
 * <p>"Exercises" is computed from real javac references: a {@link TestNode}'s {@link TestNode#referencedTypes()} are the
 * production type FQNs the test's source actually refers to (resolved through the {@link CallGraph}/symbol references,
 * not import-string guessing). {@link #testsReferencing(String)} therefore answers "which tests are likely affected if I
 * change this type" with compiler-grounded edges, which the report's {@code likelyAffectedTests} section consumes.
 *
 * @param tests every test type with the production types it references
 */
public record TestGraph(List<TestNode> tests) {

    /**
     * A test type and the production types it references.
     *
     * @param testFqn         the test type FQN
     * @param relativePath    project-relative declaring file
     * @param referencedTypes the production type FQNs this test references
     */
    public record TestNode(String testFqn, String relativePath, Set<String> referencedTypes) {
    }

    /** Test types that reference {@code fqn}. */
    public List<TestNode> testsReferencing(String fqn) {
        return tests.stream().filter(test -> test.referencedTypes().contains(fqn)).toList();
    }
}
