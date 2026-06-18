package io.serena.javarefactor.v3.packages;

/**
 * A planner precondition refusal that carries its canonical registry code (refactor-feature-plan-V3.md §4).
 *
 * <p>The v3 package planners throw a private {@code Refusal} from {@code planStep} to abort with a precise semantic code
 * (e.g. {@code package_not_found}, {@code package_collision}, {@code malformed_rename_package}). The transformation
 * step-planner dispatch catches the carrier generically; implementing this interface lets it recover the planner's real
 * code instead of flattening every refusal to a generic {@code <operation>_failed}.</p>
 */
public interface CodedRefusal {
    String code();
}
