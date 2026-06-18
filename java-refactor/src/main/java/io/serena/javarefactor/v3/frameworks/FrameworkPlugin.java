package io.serena.javarefactor.v3.frameworks;

import java.util.Map;

/**
 * A framework recognized by the V3 framework SPI (refactor-feature-plan-V3.md §16). A plugin is identified by a stable
 * id and declares the fully-qualified annotation types it owns, each mapped to a semantic <em>role</em> (e.g. Spring's
 * {@code @Service} → {@code SERVICE}, JPA's {@code @Entity} → {@code ENTITY}).
 *
 * <p>The read-only halves of the SPI ({@code detect}, {@code findReferences}) are independently-callable protocol ops
 * backed by exact compiler-resolved annotation facts. The same facts — sourced from the shared
 * {@link io.serena.javarefactor.compiler.FrameworkAnnotationCatalog} that backs this plugin's {@link #annotationRoles()}
 * — also feed the planners' deletion conservatism: {@code ReachabilityGraph} treats a type carrying one of these exact
 * annotations as a framework entry point, so a framework-managed type makes deletion <em>more</em> conservative (never
 * more aggressive). Resource-side edits (exact class names in Spring/JPA XML) ship through the package planners'
 * {@code rewrite_resources} path (§15); string-only references (Spring string bean names, JPQL) are surfaced as
 * review-required rather than rewritten.
 */
public interface FrameworkPlugin {

    /** Stable framework identifier (e.g. {@code spring}, {@code jpa}, {@code jackson}, {@code junit}). */
    String id();

    /**
     * Map of fully-qualified annotation type → semantic role this framework assigns it. This is the read-only half of
     * the SPI: it backs the {@code frameworks.detect} (which frameworks are present) and {@code frameworks.findReferences}
     * (framework-significant references to a target) protocol ops, both driven centrally from these exact FQNs by
     * {@link FrameworkScanner}.
     */
    Map<String, String> annotationRoles();

    /**
     * The transformation-participant half of the SPI (refactor-feature-plan-V3.md §16). Given a pending
     * {@link SymbolChange} and the project's compiler-resolved framework facts in {@link TransformationContext}, the
     * plugin returns the contribution it wants folded into the plan: deletion vetoes, review-required warnings,
     * framework-owned resource-edit descriptions, and reachability roots (see {@link FrameworkParticipation}). A plugin
     * with nothing to say returns {@link FrameworkParticipation#empty()}. Participation only ever makes an operation
     * <em>more</em> conservative (it blocks/roots/warns; it never authorizes a deletion the core would refuse).
     */
    FrameworkParticipation participate(SymbolChange change, TransformationContext context);
}
