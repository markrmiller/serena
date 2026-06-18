package io.serena.javarefactor.v3.recipes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plan-mandated built-in API-migration recipes (refactor-feature-plan-V3.md §14.4). Each is built as a nested
 * {@code Map} in the exact §14.1 wire shape and parsed through {@link RecipeParser}, so a built-in is validated by the
 * same path as a caller-supplied {@code recipe_json}.
 *
 * <p><b>Plan-mandated set (§14.4), all five present, no extras:</b>
 * <ul>
 *   <li>{@code junit4-to-junit5-basic}</li>
 *   <li>{@code javax-to-jakarta-basic}</li>
 *   <li>{@code deprecated-guava-optional-to-java-optional}</li>
 *   <li>{@code thread-stop-suspend-destroy-removal}</li>
 *   <li>{@code date-calendar-to-java-time-basic}</li>
 * </ul>
 *
 * <p>Risk follows §14.3 conservatively: mechanical package/annotation swaps are {@code safe}; anything that can change
 * nullability, exception behaviour, argument order or value type is {@code needs_review}; migrations with no
 * semantics-preserving replacement (removed {@code Thread} methods, {@code java.util.Date}/{@code Calendar} modernization)
 * are emitted as report-only {@code needs_review} findings (no edit), never silent rewrites.
 */
public final class BuiltinRecipes {

    private static final String GUAVA_OPTIONAL = "com.google.common.base.Optional";
    private static final String JAVA_OPTIONAL = "java.util.Optional";

    private BuiltinRecipes() {
    }

    public static Set<String> ids() {
        return new LinkedHashSet<>(List.of(
                "junit4-to-junit5-basic",
                "javax-to-jakarta-basic",
                "deprecated-guava-optional-to-java-optional",
                "thread-stop-suspend-destroy-removal",
                "date-calendar-to-java-time-basic"));
    }

    public static Recipe get(String id) {
        Map<String, Object> recipe = switch (id) {
            case "junit4-to-junit5-basic" -> junit4ToJunit5();
            case "javax-to-jakarta-basic" -> javaxToJakarta();
            case "deprecated-guava-optional-to-java-optional" -> guavaOptionalToJava();
            case "thread-stop-suspend-destroy-removal" -> threadStopRemoval();
            case "date-calendar-to-java-time-basic" -> dateCalendarToJavaTime();
            default -> null;
        };
        if (recipe == null) {
            throw new RecipeRefusal("recipe_not_found", "No built-in recipe with id '" + id + "'.");
        }
        return RecipeParser.parse(recipe);
    }

    // ── §14.4 junit4 → junit5 (annotation/type package swaps) ────────────────────────────────────────────────────

    private static Map<String, Object> junit4ToJunit5() {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(replaceType("org.junit.Test", "org.junit.jupiter.api.Test", "safe"));
        rules.add(replaceType("org.junit.Before", "org.junit.jupiter.api.BeforeEach", "safe"));
        rules.add(replaceType("org.junit.After", "org.junit.jupiter.api.AfterEach", "safe"));
        rules.add(replaceType("org.junit.BeforeClass", "org.junit.jupiter.api.BeforeAll", "safe"));
        rules.add(replaceType("org.junit.AfterClass", "org.junit.jupiter.api.AfterAll", "safe"));
        rules.add(replaceType("org.junit.Ignore", "org.junit.jupiter.api.Disabled", "safe"));
        // Assert → Assertions changes the message-argument position (message moves to last), so it cannot be applied
        // blindly: flag it for review.
        rules.add(replaceType("org.junit.Assert", "org.junit.jupiter.api.Assertions", "needs_review"));
        return recipe("junit4-to-junit5-basic", "Migrate basic JUnit 4 annotations/types to JUnit 5 (Jupiter).", rules);
    }

    // ── §14.4 javax → jakarta (curated common-type subset; full prefix migration needs a new primitive — flagged) ──

    private static Map<String, Object> javaxToJakarta() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String[] pair : new String[][] {
                {"javax.servlet.http.HttpServlet", "jakarta.servlet.http.HttpServlet"},
                {"javax.servlet.http.HttpServletRequest", "jakarta.servlet.http.HttpServletRequest"},
                {"javax.servlet.http.HttpServletResponse", "jakarta.servlet.http.HttpServletResponse"},
                {"javax.servlet.http.HttpSession", "jakarta.servlet.http.HttpSession"},
                {"javax.servlet.Filter", "jakarta.servlet.Filter"},
                {"javax.servlet.FilterChain", "jakarta.servlet.FilterChain"},
                {"javax.servlet.ServletException", "jakarta.servlet.ServletException"},
                {"javax.servlet.ServletContext", "jakarta.servlet.ServletContext"},
                {"javax.servlet.annotation.WebServlet", "jakarta.servlet.annotation.WebServlet"},
                {"javax.servlet.annotation.WebFilter", "jakarta.servlet.annotation.WebFilter"},
                {"javax.persistence.Entity", "jakarta.persistence.Entity"},
                {"javax.persistence.Id", "jakarta.persistence.Id"},
                {"javax.persistence.GeneratedValue", "jakarta.persistence.GeneratedValue"},
                {"javax.persistence.Table", "jakarta.persistence.Table"},
                {"javax.persistence.Column", "jakarta.persistence.Column"},
                {"javax.persistence.EntityManager", "jakarta.persistence.EntityManager"},
                {"javax.persistence.EntityManagerFactory", "jakarta.persistence.EntityManagerFactory"},
                {"javax.persistence.PersistenceContext", "jakarta.persistence.PersistenceContext"},
                {"javax.persistence.OneToMany", "jakarta.persistence.OneToMany"},
                {"javax.persistence.ManyToOne", "jakarta.persistence.ManyToOne"},
                {"javax.persistence.JoinColumn", "jakarta.persistence.JoinColumn"},
                {"javax.validation.Valid", "jakarta.validation.Valid"},
                {"javax.validation.constraints.NotNull", "jakarta.validation.constraints.NotNull"},
                {"javax.validation.constraints.NotBlank", "jakarta.validation.constraints.NotBlank"},
                {"javax.validation.constraints.Size", "jakarta.validation.constraints.Size"},
                {"javax.validation.constraints.Min", "jakarta.validation.constraints.Min"},
                {"javax.validation.constraints.Max", "jakarta.validation.constraints.Max"},
                {"javax.annotation.PostConstruct", "jakarta.annotation.PostConstruct"},
                {"javax.annotation.PreDestroy", "jakarta.annotation.PreDestroy"},
                {"javax.annotation.Resource", "jakarta.annotation.Resource"},
                {"javax.inject.Inject", "jakarta.inject.Inject"},
                {"javax.inject.Named", "jakarta.inject.Named"},
                {"javax.inject.Singleton", "jakarta.inject.Singleton"},
                {"javax.inject.Provider", "jakarta.inject.Provider"},
        }) {
            rules.add(replaceType(pair[0], pair[1], "safe"));
        }
        return recipe("javax-to-jakarta-basic",
                "Migrate a curated common subset of javax.* EE types to jakarta.* (Jakarta EE 9).", rules);
    }

    // ── §14.4 Guava Optional → java.util.Optional ────────────────────────────────────────────────────────────────

    private static Map<String, Object> guavaOptionalToJava() {
        List<Map<String, Object>> rules = new ArrayList<>();
        // Guava and JDK Optional differ (get() throwing, or()/orElse() semantics), so every rule is review-required.
        rules.add(replaceType(GUAVA_OPTIONAL, JAVA_OPTIONAL, "needs_review"));
        rules.add(staticCall(GUAVA_OPTIONAL, "absent", List.of(), "Optional.empty()", List.of(), "needs_review"));
        rules.add(staticCall(GUAVA_OPTIONAL, "fromNullable", List.of("java.lang.Object"),
                "Optional.ofNullable(${arg0})", List.of(), "needs_review"));
        rules.add(instanceCall(GUAVA_OPTIONAL, "or", List.of("java.lang.Object"),
                "${receiver}.orElse(${arg0})", List.of(), "needs_review"));
        return recipe("deprecated-guava-optional-to-java-optional",
                "Migrate deprecated Guava Optional to java.util.Optional (review-required: semantics differ).", rules);
    }

    // ── §14.4 Thread.stop/suspend/resume/destroy removal (report-only — no safe replacement) ──────────────────────

    private static Map<String, Object> threadStopRemoval() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String method : List.of("stop", "suspend", "resume", "destroy")) {
            rules.add(reportCall("java.lang.Thread", method, "needs_review"));
        }
        return recipe("thread-stop-suspend-destroy-removal",
                "Flag calls to removed/unsafe Thread.stop/suspend/resume/destroy for manual migration.", rules);
    }

    // ── §14.4 java.util.Date/Calendar → java.time (report-only modernization candidates) ─────────────────────────

    private static Map<String, Object> dateCalendarToJavaTime() {
        List<Map<String, Object>> rules = new ArrayList<>();
        // Replacing Date/Calendar changes the static type, so these are flagged as modernization candidates rather than
        // applied automatically.
        rules.add(reportCtor("java.util.Date", "needs_review"));
        rules.add(reportCtor("java.util.GregorianCalendar", "needs_review"));
        rules.add(reportCall("java.util.Calendar", "getInstance", "needs_review"));
        return recipe("date-calendar-to-java-time-basic",
                "Flag java.util.Date/Calendar usage as java.time modernization candidates.", rules);
    }

    // ── builders ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> recipe(String id, String description, List<Map<String, Object>> rules) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("description", description);
        map.put("rules", rules);
        return map;
    }

    private static Map<String, Object> replaceType(String oldType, String newType, String risk) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("kind", "replaceType");
        rule.put("oldType", oldType);
        rule.put("newType", newType);
        rule.put("risk", risk);
        return rule;
    }

    private static Map<String, Object> staticCall(String owner, String name, List<String> paramTypes,
                                                  String replacement, List<String> requiredImports, String risk) {
        return call("replaceStaticMethodCall", owner, name, paramTypes, replacement, requiredImports, risk);
    }

    private static Map<String, Object> instanceCall(String owner, String name, List<String> paramTypes,
                                                    String replacement, List<String> requiredImports, String risk) {
        return call("replaceMethodCall", owner, name, paramTypes, replacement, requiredImports, risk);
    }

    private static Map<String, Object> call(String kind, String owner, String name, List<String> paramTypes,
                                            String replacement, List<String> requiredImports, String risk) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("kind", kind);
        rule.put("owner", owner);
        rule.put("name", name);
        rule.put("paramTypes", paramTypes);
        if (replacement != null) {
            rule.put("replacement", replacement);
        }
        rule.put("requiredImports", requiredImports);
        rule.put("risk", risk);
        return rule;
    }

    /** A report-only method-call finding: matched and flagged, but no replacement is emitted. */
    private static Map<String, Object> reportCall(String owner, String name, String risk) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("kind", "replaceMethodCall");
        rule.put("owner", owner);
        rule.put("name", name);
        rule.put("risk", risk);
        return rule;
    }

    /** A report-only constructor finding: matched and flagged, but no replacement is emitted. */
    private static Map<String, Object> reportCtor(String owner, String risk) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("kind", "replaceConstructor");
        rule.put("owner", owner);
        rule.put("risk", risk);
        return rule;
    }
}
