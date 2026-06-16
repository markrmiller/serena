package io.serena.javarefactor.session;

import io.serena.javarefactor.protocol.Json;
import io.serena.javarefactor.protocol.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The incremental-apply selection model for a refactor session (G001).
 *
 * <p>A session's validated plan is a set of independently-identifiable units: one {@code change} per edited file (its
 * text edits) and one entry per {@code fileOperation} (create/delete/rename). Each unit carries a STABLE id derived
 * deterministically from the plan content — a change id from its path, an edit id from {@code path@start-end}, a
 * file-operation id from {@code fileop:kind:path} — so a caller can name exactly which units to apply now and so the
 * session can track which remain unapplied across multiple partial applies.
 *
 * <p>{@link #select} filters the plan's {@code workspaceEdit} down to the requested units (intersected with the units
 * not yet applied), producing the overlay to validate + apply, the set of unit ids that selection covers, the
 * project-relative paths it touches (for the revision guard), and a {@code remaining} report of what is still unapplied.
 * Selection input may name {@code files} (every edit + file-operation on those paths), explicit {@code editIds} /
 * {@code fileOperationIds}, or {@code phases} (edit {@code kind} groups). An absent/empty selection means "every
 * remaining unit".
 */
public final class SessionSelection {
    private SessionSelection() {
    }

    /** The result of resolving a selection against a plan and the already-applied unit ids. */
    public record Resolution(
            String filteredWorkspaceEditJson,
            Set<String> selectedUnitIds,
            Set<String> touchedPaths,
            boolean empty,
            boolean complete,
            String remainingJson,
            String selectionModelJson,
            String refusalCode,
            String refusalMessage) {
        public boolean refused() {
            return refusalCode != null;
        }
    }

    private record Unit(String id, String type, String path, String kind, String json) {
    }

    /**
     * Resolves {@code selection} against the plan's workspace edit and the {@code appliedUnitIds} already applied by
     * earlier partial applies. {@code selection} may be {@code null} (every remaining unit).
     */
    public static Resolution select(String planJson, Object selection, Set<String> appliedUnitIds) {
        List<Unit> units = parseUnits(planJson);
        if (units.isEmpty()) {
            return refusal("empty_session_plan", "The session plan carries no applicable edits or file operations.");
        }
        Set<String> applied = appliedUnitIds == null ? Set.of() : appliedUnitIds;
        List<Unit> remaining = units.stream().filter(unit -> !applied.contains(unit.id())).toList();

        SelectionRequest request = SelectionRequest.parse(selection);
        List<Unit> selected = new ArrayList<>();
        for (Unit unit : remaining) {
            if (request.matches(unit)) {
                selected.add(unit);
            }
        }
        // A named selection that matches nothing among the remaining units is a caller error, not a silent no-op.
        if (request.isExplicit() && selected.isEmpty()) {
            return refusal("empty_selection",
                    "The requested selection matched no remaining session edit; nothing would be applied.");
        }

        String filtered = filteredWorkspaceEditJson(planJson, selected);
        Set<String> selectedIds = new LinkedHashSet<>();
        Set<String> touched = new TreeSet<>();
        for (Unit unit : selected) {
            selectedIds.add(unit.id());
            if (unit.path() != null && !unit.path().isBlank()) {
                touched.add(unit.path());
            }
        }
        Set<String> appliedAfter = new LinkedHashSet<>(applied);
        appliedAfter.addAll(selectedIds);
        boolean complete = appliedAfter.size() >= units.size();
        String remainingJson = remainingJson(units, appliedAfter);
        String selectionModelJson = selectionModelJson(units, applied, selectedIds);
        return new Resolution(filtered, selectedIds, touched, selected.isEmpty(), complete,
                remainingJson, selectionModelJson, null, null);
    }

    /**
     * Reports a session's authoritative incremental state from ONLY the set of unit ids an ack has recorded as
     * committed to disk (G001). Unlike {@link #select}, this selects nothing and never folds in-flight units into the
     * applied set: {@code complete} is true only when every plan unit has actually been acknowledged, and
     * {@code remaining} lists the units still uncommitted. A {@code null}/empty applied set therefore yields the full
     * plan as remaining (not "all selected"), which is the correct post-commit truth.
     */
    public static Resolution describeApplied(String planJson, Set<String> appliedUnitIds) {
        List<Unit> units = parseUnits(planJson);
        if (units.isEmpty()) {
            return refusal("empty_session_plan", "The session plan carries no applicable edits or file operations.");
        }
        Set<String> applied = appliedUnitIds == null ? Set.of() : appliedUnitIds;
        Set<String> unitIds = new LinkedHashSet<>();
        for (Unit unit : units) {
            unitIds.add(unit.id());
        }
        boolean complete = applied.containsAll(unitIds);
        String remainingJson = remainingJson(units, applied);
        String selectionModelJson = selectionModelJson(units, applied, Set.of());
        return new Resolution(null, Set.of(), Set.of(), false, complete, remainingJson, selectionModelJson, null, null);
    }

    private static Resolution refusal(String code, String message) {
        return new Resolution(null, Set.of(), Set.of(), true, false, null, null, code, message);
    }

    /** The project-relative paths touched by the given unit ids in {@code planJson} (for the incremental revision guard). */
    public static Set<String> pathsFor(String planJson, Set<String> unitIds) {
        Set<String> paths = new TreeSet<>();
        if (unitIds == null || unitIds.isEmpty()) {
            return paths;
        }
        for (Unit unit : parseUnits(planJson)) {
            if (unitIds.contains(unit.id()) && unit.path() != null && !unit.path().isBlank()) {
                paths.add(unit.path());
            }
        }
        return paths;
    }

    private static List<Unit> parseUnits(String planJson) {
        List<Unit> units = new ArrayList<>();
        Object workspaceEdit;
        try {
            workspaceEdit = Json.parseObject(planJson).get("workspaceEdit");
        } catch (RuntimeException ignored) {
            return units;
        }
        if (!(workspaceEdit instanceof Map<?, ?> edit)) {
            return units;
        }
        Object changes = edit.get("changes");
        if (changes instanceof List<?> changeList) {
            for (Object changeValue : changeList) {
                if (!(changeValue instanceof Map<?, ?> change)) {
                    continue;
                }
                Object pathValue = change.get("path");
                if (!(pathValue instanceof String path)) {
                    continue;
                }
                Object editsValue = change.get("edits");
                if (!(editsValue instanceof List<?> edits)) {
                    continue;
                }
                for (Object editValue : edits) {
                    if (!(editValue instanceof Map<?, ?> textEdit)) {
                        continue;
                    }
                    long start = longField(textEdit, "startOffset");
                    long end = longField(textEdit, "endOffset");
                    String kind = stringField(textEdit, "kind");
                    String id = "edit:" + path + "@" + start + "-" + end;
                    units.add(new Unit(id, "edit", path, kind, Json.write(textEdit)));
                }
            }
        }
        Object fileOperations = edit.get("fileOperations");
        if (fileOperations instanceof List<?> opList) {
            for (Object opValue : opList) {
                if (!(opValue instanceof Map<?, ?> op)) {
                    continue;
                }
                String kind = stringField(op, "kind");
                String path = fileOperationPath(op);
                String id = "fileop:" + kind + ":" + path;
                units.add(new Unit(id, "fileOperation", path, kind, Json.write(op)));
            }
        }
        return units;
    }

    /** Rebuilds the plan's {@code workspaceEdit} object keeping only the selected units (changes grouped by path). */
    private static String filteredWorkspaceEditJson(String planJson, List<Unit> selected) {
        // Group selected text edits by their file path, preserving each change's other fields (oldSha256, etc.).
        Map<String, Map<?, ?>> changeTemplates = new LinkedHashMap<>();
        Map<String, List<String>> editJsonByPath = new LinkedHashMap<>();
        List<String> fileOpJson = new ArrayList<>();
        Map<?, ?> workspaceEdit;
        try {
            Object value = Json.parseObject(planJson).get("workspaceEdit");
            workspaceEdit = value instanceof Map<?, ?> map ? map : Map.of();
        } catch (RuntimeException ignored) {
            workspaceEdit = Map.of();
        }
        indexChangeTemplates(workspaceEdit, changeTemplates);

        for (Unit unit : selected) {
            if ("edit".equals(unit.type())) {
                editJsonByPath.computeIfAbsent(unit.path(), key -> new ArrayList<>()).add(unit.json());
            } else if ("fileOperation".equals(unit.type())) {
                fileOpJson.add(unit.json());
            }
        }

        List<String> changeObjects = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : editJsonByPath.entrySet()) {
            String path = entry.getKey();
            Map<?, ?> template = changeTemplates.get(path);
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            if (template != null) {
                for (Map.Entry<?, ?> field : template.entrySet()) {
                    String key = String.valueOf(field.getKey());
                    if ("edits".equals(key)) {
                        continue;
                    }
                    fields.put(key, Json.write(field.getValue()));
                }
            }
            if (!fields.containsKey("path")) {
                fields.put("path", JsonUtil.quote(path));
            }
            fields.put("edits", "[" + String.join(",", entry.getValue()) + "]");
            changeObjects.add(JsonUtil.object(fields));
        }

        return "{\"changes\":[" + String.join(",", changeObjects) + "]"
                + ",\"fileOperations\":[" + String.join(",", fileOpJson) + "]"
                + ",\"warnings\":[],\"preconditions\":[]}";
    }

    @SuppressWarnings("unchecked")
    private static void indexChangeTemplates(Map<?, ?> workspaceEdit, Map<String, Map<?, ?>> changeTemplates) {
        Object changes = workspaceEdit.get("changes");
        if (changes instanceof List<?> changeList) {
            for (Object changeValue : changeList) {
                if (changeValue instanceof Map<?, ?> change && change.get("path") instanceof String path) {
                    changeTemplates.put(path, (Map<String, Object>) change);
                }
            }
        }
    }

    private static String remainingJson(List<Unit> units, Set<String> appliedAfter) {
        List<String> remainingIds = new ArrayList<>();
        Set<String> remainingPaths = new TreeSet<>();
        for (Unit unit : units) {
            if (!appliedAfter.contains(unit.id())) {
                remainingIds.add(unit.id());
                if (unit.path() != null && !unit.path().isBlank()) {
                    remainingPaths.add(unit.path());
                }
            }
        }
        return "{\"unitIds\":" + JsonUtil.array(remainingIds)
                + ",\"files\":" + JsonUtil.array(new ArrayList<>(remainingPaths))
                + ",\"remainingUnitCount\":" + remainingIds.size()
                + ",\"complete\":" + remainingIds.isEmpty() + "}";
    }

    private static String selectionModelJson(List<Unit> units, Set<String> appliedBefore, Set<String> selectedNow) {
        List<String> unitObjects = new ArrayList<>();
        for (Unit unit : units) {
            boolean applied = appliedBefore.contains(unit.id()) || selectedNow.contains(unit.id());
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("id", JsonUtil.quote(unit.id()));
            fields.put("type", JsonUtil.quote(unit.type()));
            fields.put("path", JsonUtil.quote(unit.path()));
            if (unit.kind() != null) {
                fields.put("kind", JsonUtil.quote(unit.kind()));
            }
            fields.put("applied", String.valueOf(applied));
            unitObjects.add(JsonUtil.object(fields));
        }
        return "[" + String.join(",", unitObjects) + "]";
    }

    private static String fileOperationPath(Map<?, ?> op) {
        for (String key : List.of("path", "newPath", "oldPath")) {
            Object value = op.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static long longField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String stringField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        return value instanceof String text ? text : null;
    }

    /** Parsed selection request: an absent/empty request means "every remaining unit". */
    private record SelectionRequest(Set<String> files, Set<String> editIds, Set<String> fileOperationIds, Set<String> phases) {
        static SelectionRequest parse(Object selection) {
            if (!(selection instanceof Map<?, ?> map)) {
                return new SelectionRequest(Set.of(), Set.of(), Set.of(), Set.of());
            }
            return new SelectionRequest(
                    stringSet(map, "files", "paths", "changeIds"),
                    stringSet(map, "edits", "editIds"),
                    stringSet(map, "fileOperations", "fileOperationIds"),
                    stringSet(map, "phases", "kinds"));
        }

        boolean isExplicit() {
            return !files.isEmpty() || !editIds.isEmpty() || !fileOperationIds.isEmpty() || !phases.isEmpty();
        }

        boolean matches(Unit unit) {
            if (!isExplicit()) {
                return true;
            }
            if (unit.path() != null && files.contains(unit.path())) {
                return true;
            }
            if ("edit".equals(unit.type()) && editIds.contains(unit.id())) {
                return true;
            }
            if ("fileOperation".equals(unit.type()) && fileOperationIds.contains(unit.id())) {
                return true;
            }
            return unit.kind() != null && phases.contains(unit.kind());
        }

        private static Set<String> stringSet(Map<?, ?> map, String... keys) {
            Set<String> values = new LinkedHashSet<>();
            for (String key : keys) {
                Object value = map.get(key);
                if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof String text && !text.isBlank()) {
                            values.add(text);
                        }
                    }
                } else if (value instanceof String text && !text.isBlank()) {
                    values.add(text);
                }
            }
            return values;
        }
    }
}
