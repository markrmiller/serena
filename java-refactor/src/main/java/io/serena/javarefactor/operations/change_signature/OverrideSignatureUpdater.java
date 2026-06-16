package io.serena.javarefactor.operations.change_signature;

import io.serena.javarefactor.ast.IdentifierSpan;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

/**
 * G006 architecture unit: override-group propagation. Resolves the full override/interface group a target executable
 * participates in, validates that every member can be soundly rewritten (resolved, non-native, non-annotation-member,
 * editable origin), and produces the per-declaration header edits and in-body parameter-rename edits that keep the whole
 * group's signatures in lockstep. Declaration header rendering is delegated back to the caller via a renderer hook so the
 * unit stays free of import-planning concerns.
 */
public final class OverrideSignatureUpdater {
    private final SemanticIndex index;

    public OverrideSignatureUpdater(SemanticIndex index) {
        this.index = index;
    }

    /** Resolves the full set of declarations (the override/interface group) that must be rewritten together. */
    public List<MethodMatch> overrideDeclarations(MethodMatch declaration) throws SignatureRefusal {
        List<Element> group = index.overrideGroup(declaration.semantic().element());
        List<MethodMatch> declarations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element element : group) {
            SemanticIndex.SemanticMethod semantic = index.semanticMethod(element);
            if (semantic == null) {
                throw new SignatureRefusal("OVERRIDE_GROUP_INCOMPLETE", "Cannot update every declaration in the override/interface group.");
            }
            MethodMatch match = MethodMatchFactory.from(index, semantic);
            String key = match.semantic().file().toAbsolutePath().normalize() + ":" + match.start();
            if (seen.add(key)) {
                declarations.add(match);
            }
        }
        if (declarations.isEmpty()) {
            throw new SignatureRefusal("OVERRIDE_GROUP_INCOMPLETE", "Cannot resolve the selected executable declaration.");
        }
        return declarations;
    }

    /**
     * Op-specific target gate: refuses executables that cannot be soundly rewritten even after the identity gate has
     * proven the target — unresolved elements, {@code native} methods, annotation-type members, and any declaration whose
     * source file is generated or outside the editable project tree.
     */
    public void validateSupportedTargets(List<MethodMatch> declarations) throws SignatureRefusal {
        Set<Path> groupFiles = new LinkedHashSet<>();
        for (MethodMatch declaration : declarations) {
            Element element = declaration.semantic().element();
            if (element == null) {
                throw new SignatureRefusal("UNRESOLVED_TARGET", "Change signature requires a javac-resolved executable element for every declaration in the override group.");
            }
            if (declaration.semantic().modifiers().contains(Modifier.NATIVE)) {
                throw new SignatureRefusal("NATIVE_METHOD_UNSUPPORTED", "Change signature cannot rewrite native methods; the native implementation is not visible to javac.");
            }
            Element owner = element.getEnclosingElement();
            if (owner != null && owner.getKind() == ElementKind.ANNOTATION_TYPE) {
                throw new SignatureRefusal("ANNOTATION_MEMBER_UNSUPPORTED", "Change signature cannot rewrite annotation type members.");
            }
            groupFiles.add(declaration.semantic().file().toAbsolutePath().normalize());
        }
        String originRefusal = index.detectNonEditableFiles(groupFiles);
        if (originRefusal != null) {
            throw new SignatureRefusal("GENERATED_OR_DEPENDENCY_TARGET", originRefusal);
        }
    }

    /** Whether any declaration in the group is {@code public} or {@code protected} (a public-API surface). */
    public boolean publicApi(List<MethodMatch> declarations) {
        for (MethodMatch declaration : declarations) {
            Set<Modifier> modifiers = declaration.semantic().modifiers();
            if (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The header-replacement edit and in-body parameter-rename edits for a single declaration in the group. The header
     * text is produced by the supplied {@code headerRenderer} (which owns import planning); the parameter-rename edits
     * retarget references to renamed retained parameters within the declaration's own body.
     */
    public List<PlannerSupport.TextEdit> declarationEdits(
            MethodMatch declaration,
            String declarationSource,
            String header,
            List<ParameterSpec> desired) throws SignatureRefusal {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        edits.add(new PlannerSupport.TextEdit(declaration.semantic().file(), declaration.start(), declaration.headerEnd(), header, "CHANGE_SIGNATURE_DECLARATION"));
        edits.addAll(parameterRenameEdits(declarationSource, declaration.semantic().file(), declaration, desired));
        return edits;
    }

    /** In-body rename edits for retained parameters whose name changed, scoped to the declaration's own body. */
    public List<PlannerSupport.TextEdit> parameterRenameEdits(String source, Path file, MethodMatch declaration, List<ParameterSpec> desired) throws SignatureRefusal {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        int bodyStart = declaration.headerEnd();
        int bodyEnd = declaration.bodyEnd(source);
        for (int desiredIndex = 0; desiredIndex < desired.size(); desiredIndex++) {
            ParameterSpec parameter = desired.get(desiredIndex);
            int oldIndex = MethodSignatureModel.oldIndex(parameter, declaration, desiredIndex, desired);
            if (oldIndex < 0) {
                continue;
            }
            if (oldIndex >= declaration.parameters().size()) {
                throw new SignatureRefusal("parameter_index_out_of_range", "oldIndex is outside the current signature.");
            }
            String oldName = declaration.parameters().get(oldIndex).name();
            String newName = parameter.name();
            if (oldName.equals(newName)) {
                continue;
            }
            for (IdentifierSpan span : index.parameterReferences(declaration.semantic(), oldName)) {
                if (span.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())
                        && span.startOffset() >= bodyStart
                        && span.endOffset() <= bodyEnd) {
                    edits.add(new PlannerSupport.TextEdit(file, span.startOffset(), span.endOffset(), newName, "CHANGE_SIGNATURE_PARAMETER_RENAME"));
                }
            }
        }
        return edits;
    }

    /** Refuses removing a current parameter that the method body still references. */
    public void validateRemovedParameters(MethodMatch declaration, List<ParameterSpec> desired) throws SignatureRefusal {
        Set<Integer> retained = retainedParameterIndexes(declaration, desired);
        for (int parameterIndex = 0; parameterIndex < declaration.parameters().size(); parameterIndex++) {
            if (retained.contains(parameterIndex)) {
                continue;
            }
            ParameterSpec parameter = declaration.parameters().get(parameterIndex);
            for (IdentifierSpan span : index.parameterReferences(declaration.semantic(), parameter.name())) {
                CharSequence source = index.sourceText(declaration.semantic().file());
                if (source == null || span.startOffset() < declaration.headerEnd() || span.startOffset() > declaration.bodyEnd(source.toString())) {
                    continue;
                }
                throw new SignatureRefusal("REMOVED_PARAMETER_STILL_USED", "Removed parameter '" + parameter.name() + "' is still referenced by the method body.");
            }
        }
    }

    public Set<Integer> retainedParameterIndexes(MethodMatch declaration, List<ParameterSpec> desired) throws SignatureRefusal {
        Set<Integer> retained = new LinkedHashSet<>();
        for (int desiredIndex = 0; desiredIndex < desired.size(); desiredIndex++) {
            int oldIndex = MethodSignatureModel.oldIndex(desired.get(desiredIndex), declaration, desiredIndex, desired);
            if (oldIndex >= declaration.parameters().size()) {
                throw new SignatureRefusal("parameter_index_out_of_range", "oldIndex is outside the current signature.");
            }
            if (oldIndex >= 0) {
                retained.add(oldIndex);
            }
        }
        return retained;
    }

    /** Factory bridge so the unit and the planner build {@link MethodMatch} the same way. */
    public interface MethodMatchBuilder extends BiFunction<SemanticIndex, SemanticIndex.SemanticMethod, MethodMatch> {
    }
}
