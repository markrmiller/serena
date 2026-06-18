package io.serena.javarefactor.v3.resources;

import io.serena.javarefactor.v3.packages.CodedRefusal;

/**
 * A coded refusal raised by the resource-reference SPI (refactor-feature-plan-V3.md §15). Canonical codes:
 * <ul>
 *   <li>{@code resource_target_unresolved} — no usable target was supplied.</li>
 *   <li>{@code unsupported_resource_kind} — a requested {@code kind} filter is not a known resource-reference kind.</li>
 * </ul>
 */
public final class ResourceRefusal extends RuntimeException implements CodedRefusal {

    private final String code;

    public ResourceRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
