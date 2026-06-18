package io.serena.javarefactor.v3.recipes;

import io.serena.javarefactor.compiler.RecipeMatchIndex.RecipeRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an inbound recipe object (already decoded to a nested {@code Map}/{@code List} by the protocol's
 * {@code Json.parseObject} + {@code flatten}) into a structurally-validated {@link Recipe}
 * (refactor-feature-plan-V3.md §14.1). This is a <em>structural</em> pass only — it verifies that every rule has a known
 * kind and the fields that kind requires, and that any replacement template references only the supported
 * {@code ${receiver}}/{@code ${argN}} placeholders. Whether the referenced types/methods actually resolve is decided
 * later by javac in {@link io.serena.javarefactor.compiler.RecipeMatchIndex}.
 */
public final class RecipeParser {

    /** §14.1 rule kinds that this engine implements. */
    private static final Set<String> SUPPORTED_KINDS = Set.of(
            "replaceMethodCall", "replaceStaticMethodCall", "replaceConstructor", "replaceFieldAccess",
            "replaceType", "replaceImport", "replaceAnnotation", "removeAnnotation", "addAnnotation");

    /**
     * §14.1 structural rule kind: {@code changeMethodSignature} re-orders/adds/removes/retypes parameters (and may rename
     * or change the return type) with cascading call-site, override and javadoc updates. It is parsed into a
     * {@link SignatureChangeRule} and driven by the recipe engine through the compiler-backed change-signature operation
     * — a real refactoring, not a match-and-template replacement.
     */
    private static final String SIGNATURE_CHANGE_KIND = "changeMethodSignature";

    /**
     * Desired-signature fields a {@code changeMethodSignature} rule may carry; copied verbatim into the rule's
     * {@code change} map and passed straight to the change-signature operation. At least one must be present so the rule
     * is not a silent no-op.
     */
    private static final Set<String> SIGNATURE_CHANGE_KEYS = Set.of(
            "parameters", "newName", "newReturnType", "confirmPublicApi", "confirmPublicApiChange",
            "removeParameters", "returnConversion", "bodyReturnConversion", "allowRemovedSideEffectingArguments");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern ARG_PLACEHOLDER = Pattern.compile("arg\\d+");

    private RecipeParser() {
    }

    public static Recipe parse(Map<String, Object> recipeMap) {
        if (recipeMap == null) {
            throw new RecipeRefusal("malformed_recipe", "Recipe payload is missing.");
        }
        String id = optString(recipeMap, "id");
        String description = optString(recipeMap, "description");
        Object rulesObj = recipeMap.get("rules");
        if (!(rulesObj instanceof List<?> ruleList) || ruleList.isEmpty()) {
            throw new RecipeRefusal("malformed_recipe", "Recipe '" + (id == null ? "?" : id) + "' has no rules.");
        }
        List<RecipeRule> rules = new ArrayList<>();
        List<SignatureChangeRule> signatureRules = new ArrayList<>();
        for (int i = 0; i < ruleList.size(); i++) {
            Object entry = ruleList.get(i);
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new RecipeRefusal("malformed_recipe", "Rule #" + i + " is not an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ruleMap = (Map<String, Object>) raw;
            if (SIGNATURE_CHANGE_KIND.equals(optString(ruleMap, "kind"))) {
                signatureRules.add(parseSignatureRule(ruleMap, i));
            } else {
                rules.add(parseRule(ruleMap, i));
            }
        }
        return new Recipe(id, description, rules, signatureRules);
    }

    private static SignatureChangeRule parseSignatureRule(Map<String, Object> rule, int index) {
        String id = optString(rule, "id");
        if (id == null || id.isBlank()) {
            id = SIGNATURE_CHANGE_KIND + "#" + index;
        }
        String owner = optString(rule, "owner");
        String name = optString(rule, "name");
        require(owner, "owner", id);
        require(name, "name", id);
        List<String> paramTypes = stringList(rule.get("paramTypes"));
        Map<String, Object> change = new LinkedHashMap<>();
        for (String key : SIGNATURE_CHANGE_KEYS) {
            if (rule.containsKey(key)) {
                change.put(key, rule.get(key));
            }
        }
        if (change.isEmpty()) {
            throw new RecipeRefusal("malformed_recipe", "changeMethodSignature rule '" + id + "' requests no change; "
                    + "supply at least one of parameters/newName/newReturnType.");
        }
        return new SignatureChangeRule(id, owner, name, paramTypes, change);
    }

    private static RecipeRule parseRule(Map<String, Object> rule, int index) {
        String kind = optString(rule, "kind");
        if (kind == null || kind.isBlank()) {
            throw new RecipeRefusal("malformed_recipe", "Rule #" + index + " is missing 'kind'.");
        }
        if (!SUPPORTED_KINDS.contains(kind)) {
            throw new RecipeRefusal("recipe_unknown_rule_kind", "Rule #" + index + " has unknown kind '" + kind + "'.");
        }
        String id = optString(rule, "id");
        if (id == null || id.isBlank()) {
            id = kind + "#" + index;
        }
        String owner = optString(rule, "owner");
        String name = optString(rule, "name");
        List<String> paramTypes = stringList(rule.get("paramTypes"));
        String replacement = optString(rule, "replacement");
        List<String> requiredImports = stringList(rule.get("requiredImports"));
        String oldType = optString(rule, "oldType");
        String newType = optString(rule, "newType");
        String risk = optString(rule, "risk");

        switch (kind) {
            case "replaceMethodCall", "replaceStaticMethodCall", "replaceFieldAccess" -> {
                require(owner, "owner", id);
                require(name, "name", id);
            }
            case "replaceConstructor" -> require(owner, "owner", id);
            case "replaceType", "replaceImport", "replaceAnnotation" -> {
                require(oldType, "oldType", id);
                require(newType, "newType", id);
            }
            case "removeAnnotation" -> require(owner, "owner", id);
            case "addAnnotation" -> {
                require(owner, "owner", id);
                require(newType, "newType", id);
            }
            default -> throw new RecipeRefusal("recipe_unknown_rule_kind", "Unhandled rule kind '" + kind + "'.");
        }
        validateTemplate(replacement, id);
        return new RecipeRule(id, kind, owner, name, paramTypes, replacement, requiredImports, oldType, newType, risk);
    }

    private static void validateTemplate(String template, String ruleId) {
        if (template == null) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!"receiver".equals(token) && !ARG_PLACEHOLDER.matcher(token).matches()) {
                throw new RecipeRefusal("recipe_unsupported_template",
                        "Rule '" + ruleId + "' uses unsupported template placeholder '${" + token + "}'. "
                                + "Only ${receiver} and ${argN} are supported.");
            }
        }
    }

    private static void require(String value, String field, String ruleId) {
        if (value == null || value.isBlank()) {
            throw new RecipeRefusal("malformed_recipe", "Rule '" + ruleId + "' is missing required field '" + field + "'.");
        }
    }

    private static String optString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new RecipeRefusal("malformed_recipe", "Expected an array but found: " + value);
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                out.add(item.toString());
            }
        }
        return out;
    }
}
