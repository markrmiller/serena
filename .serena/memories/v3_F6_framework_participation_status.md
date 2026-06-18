# F6 — Framework participation pipeline (task #54, IN PROGRESS)

Mandate: full V3 build-out, all-or-nothing, no documenting-away. Sub-deliverables: blocking facts, edits, validation, impact.

## DONE + verified (12 tests green under JDK21; jar built JDK17 exit 0)
- **Blocking facts (the core integrity fix).** Replaced the forbidden simple-name `FRAMEWORK_ANNOTATIONS` heuristic in `compiler/ReachabilityGraph.java` with EXACT-FQN matching via new single source of truth `compiler/FrameworkAnnotationCatalog.java` (Spring/JPA(jakarta+javax)/Jackson/JUnit(jupiter+4) → frameworkId+role; `entryPointReason(fqn)`). `frameworkReason(Element)` resolves each annotation mirror's exact FQN and asks the catalog.
- JSR-330/250 `@Inject/@Resource/@PostConstruct/@PreDestroy` + `@Override` reclassified as STRUCTURAL roots (not framework) in `structuralReason(...)`.
- `v3/frameworks/FrameworkPlugins.java` now derives each plugin's annotation→role map from the catalog (registry/SPI + planner conservatism share one truth).
- Honesty: removed "deferred to Phase 7 / not wired into planners" from `FrameworkPlugin.java` + `FrameworkScanner.java` doc comments (those claims are now false).
- Proof test: `test_sidecar_safe_delete_blocks_exact_framework_annotation_not_lookalike` — real `org.springframework.stereotype.Service` orphan BLOCKED ("framework entry point" + exact FQN in reason); lookalike `com.acme.anno.Service` orphan stays DELETABLE. In `test/serena/test_java_refactor_sidecar_v3_safe_delete.py`.
- SPI protocol suite `test_java_refactor_v3_framework_spi_protocol.py` stays green (catalog preserves TEST/EXTEND_WITH roles + all FQNs).

## DONE earlier (F2/F5) — covers part of F6 "edits"
- XML exact-class rewrites (Spring `<bean class=...>`, JPA `persistence.xml <class>`) ship via generic resource layer (`v3/resources/XmlResourceProvider` EXACT_CLASS_NAME/PACKAGE_PREFIX, HIGH) — rewritten on rename/move (`ResourceRewriter`), surfaced as dangling-review warnings on safe-delete (`danglingResourceReferenceWarnings`).

## KEY ARCHITECTURAL INSIGHT (verified 2026-06-19)
Framework-managed types (`@Service/@Entity/@Embeddable/@RequestMapping/...`) are now UNCONDITIONALLY blocked from
safe-delete (`isCascadeRoot` returns true for frameworkEntry regardless of honorPublicApi). Therefore the delete path's
framework participation is PURELY the blocking facts (DONE). ALL framework EDIT/WARNING/VALIDATION participation
(string-bean review, JPQL review, XML class rewrite) is structurally a RENAME/MOVE/ENCAPSULATE concern — NEVER delete.
A JPQL/string-bean review warning wired into safe-delete is a guaranteed silent no-op (the entity/bean can never be in
`deletedTypeFqns`) = the exact dishonesty the review flags. (Attempted+REVERTED a jpaNamedQueryReviewWarnings in
PropagatingSafeDeletePlanner for this reason — do NOT re-add it there.)

Also: package rename/move do NOT change a JPA entity's name (= simple class name), so JPQL is unaffected by package
ops. JPQL/string-bean review only matters on TYPE rename (semanticRename), which changes the simple name.

## DONE 2026-06-19 — type-rename framework review (JPQL + Spring string bean-name) — 92 tests green
- NEW `compiler/FrameworkRenameReview.java` (compiler layer, beside catalog/index so both V2 rename + V3 planners can
  call it without v3/edits dependency). `reviewWarnings(SemanticIndex, projectRoot, renamedTypeFqn, oldSimple, newSimple)`
  returns review-required warning strings, EXACT-FQN-gated on the renamed type's own catalog roles:
  * JPA: if type carries ENTITY role -> scan ALL NamedQuery/NamedQueries occurrences whose argumentText whole-word-
    matches old simple name (default entity name) -> warn per occurrence (NOT rewritten; JPQL not parsed).
  * Spring: if type carries a stereotype role (COMPONENT/SERVICE/REPOSITORY/CONTROLLER/REST_CONTROLLER/CONFIGURATION) ->
    oldBean=Introspector-style decapitalize(oldSimple) -> scan Qualifier occurrences whole-word-matching oldBean -> warn.
  Only emits when a real occurrence exists (no vacuous caveat). Whole-word regex (?<![A-Za-z0-9_$])X(?![A-Za-z0-9_$]).
- Added exact FQNs jakarta/javax.persistence.NamedQueries (role NAMED_QUERIES) to FrameworkAnnotationCatalog so the
  container annotation is recognized (else JPQL review is a near-no-op for the common @NamedQueries case).
- Wired into rename/SemanticRenamePlanner.plan(...) TypeElement branch (alongside existing reflectionResourceCaveat,
  which is KEPT — framework review AUGMENTS it). moveTopLevelType deliberately NOT wired: a package move does not change
  the simple name, so entity-name/bean-name are unaffected.
- Proof: test/serena/test_java_refactor_v3_framework_rename_review.py (5 tests): JPA NamedQuery on entity; JPA
  NamedQueries cross-entity (project-wide scan + container FQN); Spring Qualifier bean-name; lookalike com.acme.anno
  @Service does NOT trigger Spring review (honesty gate); plain @Entity w/ no JPQL emits NO JPA warning (no-op honesty).

## REMAINING F6 sub-deliverables (still blocking under all-or-nothing) — all on RENAME/MOVE/ENCAPSULATE paths
0. **DONE (committed `7de64565`; this item was STALE).** Type-rename XML exact-class rewrite IS wired. NOT via
   `v3.packages.ResourceRewriter` — instead `rename/SemanticRenamePlanner.java` (TypeElement branch, ~:148-222)
   calls the underlying `ResourcePlanner` SPI directly (the SAME single source `ResourceRewriter` delegates to, so
   no duplicated scanner): private `rewriteResourceClassReferences(...)` builds a single-FQN `ResourceRenameRequest`
   (oldFqn→newFqn) with `ResourceScanScope(true,true,true,true,true)`, threads `RESOURCE_REFERENCE:<conf>` TextEdits
   into `workspaceEdit.changes` and `ResourceFileRename`s into `fileOperations`. KEEPS `FrameworkRenameReview`
   JPQL/bean review (review-only by design) + `reflectionResourceCaveat` alongside — both participations coexist.
   Tests committed: `test/serena/test_java_refactor_v3_type_rename_resource_rewrite.py` (Spring `<bean class=>`, JPA
   `persistence.xml <class>`); coexistence pin added in `test_java_refactor_v3_framework_rename_review.py` (Wave-4 D2).
3. **Field-encapsulation framework warnings** (§16.2/§16.3): JPA access-strategy change; Jackson JSON property stability.
4. **Framework validation participant** (§18.3 / overlaps F8 #56): re-check Spring XML bean class + JPA persistence.xml
   entries resolve post-edit. CAUTION: generic resource dangling check already covers concrete cases — add genuine new
   coverage, not a duplicate shim (SLOP).
5. **Framework impact** section (overlaps F7 #55).

## Build/test
- BUILD JDK17: `cd java-refactor && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew compileJava jar -q`
- TEST JDK21: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; export PATH="$JAVA_HOME/bin:$PATH"; uv run pytest <file> -p no:cacheprovider -q`
- Serena symbolic tools = python/ts ONLY, never .java (use Read/Edit).
