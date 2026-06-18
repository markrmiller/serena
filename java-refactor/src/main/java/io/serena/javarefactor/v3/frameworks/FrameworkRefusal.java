package io.serena.javarefactor.v3.frameworks;

import io.serena.javarefactor.v3.packages.CodedRefusal;

/**
 * A coded refusal raised by the framework SPI (refactor-feature-plan-V3.md §16). Canonical code:
 * <ul>
 *   <li>{@code framework_target_unresolved} — no usable target was supplied to {@code frameworks.findReferences}.</li>
 * </ul>
 */
public final class FrameworkRefusal extends RuntimeException implements CodedRefusal {

    private final String code;

    public FrameworkRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
