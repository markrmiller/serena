package io.serena.javarefactor.v3.class_refactors;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExtractClassPlannerFacadeTest {
    @Test
    void publicFacadeDelegatesToImplementedPlanner() {
        assertTrue(io.serena.javarefactor.v3.classops.ExtractClassPlanner.class.isAssignableFrom(ExtractClassPlanner.class));
    }
}
