from typing import Any

from serena.tools.java_refactor_tools import _JavaRefactorToolBase


class JavaRenamePackageTool(_JavaRefactorToolBase):
    """Previews or applies compiler-backed Java package renames (V3 ``renamePackage``)."""

    def apply(
        self,
        old_package: str = "",
        new_package: str = "",
        include_subpackages: bool = True,
        rewrite_resources: bool | None = None,
        rewrite_module_info: bool | None = None,
        module_strategy: str | None = None,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Rename a Java package across the project using compiler-backed planning.

        Every source file whose package declaration is ``old_package`` -- and, when ``include_subpackages`` is true (the
        default), every package nested beneath it -- has its declaration rewritten (swapping the ``old_package`` prefix
        for ``new_package``, subpackages preserving their tail) and its file moved under the new package directory within
        the same source root; imports and fully-qualified references to every moved package are updated across the
        project (owner-aware, so a reference into a non-moved subpackage is left untouched). The sidecar refuses with a
        structured ``refusal`` (``package_collision``, ``package_not_found``, ``non_editable_target``, or
        ``malformed_rename_package``) rather than producing an edit that would not compile, and an accepted result
        carries a javac-validated before/after diagnostic delta.

        :param old_package: Dotted Java package whose declared types are moved (e.g. ``com.acme.app``).
        :param new_package: Dotted Java destination package (e.g. ``com.acme.core``).
        :param include_subpackages: When true (default) packages nested beneath ``old_package`` are renamed under the
            new package prefix as well (e.g. ``com.acme.app.util`` -> ``com.acme.core.util``); when false only the types
            declared directly in ``old_package`` are renamed and subpackages are left in place.
        :param rewrite_resources: When set, overrides the project's ``java_refactor.v3.packages.rewrite_resources``
            configuration for this call only: true also rewrites exact fully-qualified names in scanned resource files
            (XML/properties/YAML/JSON, ``META-INF/services``); false leaves resource files untouched. Omit to use the
            project configuration default (on).
        :param rewrite_module_info: When set, overrides the project's ``java_refactor.v3.packages.rewrite_module_info``
            configuration for this call only: true also rewrites matching ``exports``/``opens``/``uses``/``provides``
            directives in ``module-info.java``; false leaves module declarations untouched. Omit to use the project
            configuration default (on).
        :param module_strategy: Required to proceed when a moved package is split across more than one source
            root/module (the sidecar otherwise refuses with ``package_split_across_modules``). Split detection uses the
            build graph's real package-to-source-root facts for every source package -- exported/opened or not -- so a
            non-exported package present in two roots/modules is still caught. Supplying a non-blank strategy (e.g.
            ``"rewrite-all"``) signals you have decided how the split is resolved and lifts the refusal.
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation. This flag cannot weaken the apply safety gate: post-apply validation with
            rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project disables
            ``java_refactor.validate_before_apply``.
        :param allow_review_required: Uniform risk-policy control. When ``False`` (default) an edit the engine
            classifies REVIEW_REQUIRED (it crosses a public-API/framework/resource boundary or relies on a heuristic a
            human should confirm) is blocked on apply and writes nothing; set ``True`` to apply it after review. SAFE
            edits always apply and REFUSED results never apply, regardless of this flag.
        """
        result = self._get_manager().rename_package(
            old_package,
            new_package,
            include_subpackages=include_subpackages,
            rewrite_resources=rewrite_resources,
            rewrite_module_info=rewrite_module_info,
            module_strategy=module_strategy,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)


class JavaMovePackageTool(_JavaRefactorToolBase):
    """Previews or applies compiler-backed Java package moves (V3 ``movePackage``)."""

    def apply(
        self,
        source_package: str = "",
        target_package: str = "",
        include_subpackages: bool = True,
        target_source_root: str | None = None,
        rewrite_resources: bool | None = None,
        rewrite_module_info: bool | None = None,
        module_strategy: str | None = None,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Move a Java package (and, by default, its subpackages) to a target package using compiler-backed planning.

        Every source file whose package declaration is ``source_package`` -- and, when ``include_subpackages`` is true
        (the default), every file in a package nested beneath it -- has its declaration rewritten under
        ``target_package`` and its file relocated into the destination package directory, optionally beneath a different
        configured ``target_source_root``; imports and fully-qualified references to every moved package are updated
        across the project. The sidecar refuses with a structured ``refusal`` (``package_collision``,
        ``package_not_found``, ``non_editable_target``, or ``malformed_move_package``) rather than producing an edit that
        would not compile, and an accepted result carries a javac-validated before/after diagnostic delta.

        :param source_package: Dotted Java package to move (e.g. ``com.acme.app``).
        :param target_package: Dotted Java destination package (e.g. ``com.acme.core``).
        :param include_subpackages: When true (default) packages nested beneath ``source_package`` are moved under the
            corresponding destination prefix; when false only the exact ``source_package`` is moved.
        :param target_source_root: Optional project-relative source root the moved files are relocated under; when
            omitted each file stays within its current source root. An unknown root is refused as ``non_editable_target``.
        :param rewrite_resources: When set, overrides the project's ``java_refactor.v3.packages.rewrite_resources``
            configuration for this call only: true also rewrites exact fully-qualified names in scanned resource files
            (XML/properties/YAML/JSON, ``META-INF/services``); false leaves resource files untouched. Omit to use the
            project configuration default (on).
        :param rewrite_module_info: When set, overrides the project's ``java_refactor.v3.packages.rewrite_module_info``
            configuration for this call only: true also rewrites matching ``exports``/``opens``/``uses``/``provides``
            directives in ``module-info.java``; false leaves module declarations untouched. Omit to use the project
            configuration default (on).
        :param module_strategy: Required to proceed when a moved package is split across more than one source
            root/module (the sidecar otherwise refuses with ``package_split_across_modules``). Split detection uses the
            build graph's real package-to-source-root facts for every source package -- exported/opened or not -- so a
            non-exported package present in two roots/modules is still caught. Supplying a non-blank strategy (e.g.
            ``"rewrite-all"``) signals you have decided how the split is resolved and lifts the refusal.
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation. This flag cannot weaken the apply safety gate: post-apply validation with
            rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project disables
            ``java_refactor.validate_before_apply``.
        :param allow_review_required: Uniform risk-policy control. When ``False`` (default) an edit the engine
            classifies REVIEW_REQUIRED (it crosses a public-API/framework/resource boundary or relies on a heuristic a
            human should confirm) is blocked on apply and writes nothing; set ``True`` to apply it after review. SAFE
            edits always apply and REFUSED results never apply, regardless of this flag.
        """
        result = self._get_manager().move_package(
            source_package,
            target_package,
            include_subpackages=include_subpackages,
            target_source_root=target_source_root,
            rewrite_resources=rewrite_resources,
            rewrite_module_info=rewrite_module_info,
            module_strategy=module_strategy,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)


class JavaMoveSourceRootTool(_JavaRefactorToolBase):
    """Previews or applies compiler-backed Java source-root moves (V3 ``moveSourceRoot``)."""

    def apply(
        self,
        source_root: str = "",
        target_source_root: str = "",
        packages_json: str = "[]",
        include_subpackages: bool = True,
        rewrite_build_files: bool = False,
        preserve_package_names: bool = True,
        preview: bool | None = None,
        validate: bool = True,
    ) -> str:
        """
        Relocate Java source files from one configured source root to another, keeping package declarations unchanged.

        Every Java source file under ``source_root`` -- optionally restricted to ``packages_json`` (and, when
        ``include_subpackages`` is true, their subpackages) -- is moved into the matching location beneath
        ``target_source_root``. With the default ``preserve_package_names=True`` its declared package is left identical
        (a pure physical relocation). With ``preserve_package_names=False`` the new package is recomputed from the
        directory mapping (target source root + the file's relative directory) and the existing package-rename logic is
        run, so package declarations, imports, static imports, fully-qualified references, ``module-info`` directives,
        and resource references are all rewritten and javac-validated. Because no package declaration changes (in the
        default preserve mode),
        fully-qualified names and imports across the project are unaffected, so the planned workspace edit carries file
        move operations and, only when ``rewrite_build_files`` is true and the target is not yet a configured source
        root, one additive build-file edit registering the target as a ``srcDir`` of the owning source set. When the
        target is not configured and ``rewrite_build_files`` is false (the default), the sidecar refuses with
        ``BUILD_FILE_UPDATE_REQUIRED``; when it is true but the module is Maven or has no Gradle build file, it refuses
        with ``build_file_rewrite_unsupported``. Other structured refusals (``source_root_not_found``,
        ``package_collision``, ``package_not_found``, ``non_editable_target``, or ``malformed_move_source_root``)
        prevent a move that would not compile, and an accepted result carries a javac-validated before/after delta.

        :param source_root: Project-relative configured source root to move files out of (e.g. ``src/main/java``).
        :param target_source_root: Project-relative configured source root to move files into (e.g. ``src/main/java11``).
        :param packages_json: JSON array (or comma-separated list) of dotted package names to restrict the move to;
            an empty array (the default) moves every package rooted under ``source_root``.
        :param include_subpackages: When true (default) subpackages of each requested package are moved as well; when
            false only files whose declared package exactly matches a requested package are moved.
        :param rewrite_build_files: When true and ``target_source_root`` is not already a configured source root, append
            an additive Gradle ``sourceSets`` ``srcDir`` registration for it to the owning module's build file instead
            of refusing with ``BUILD_FILE_UPDATE_REQUIRED``. Only the safe additive case is supported (Gradle Groovy or
            Kotlin DSL); Maven modules and modules without a Gradle build file refuse with
            ``build_file_rewrite_unsupported``. Defaults to false (§6.3: no build-file edits by default).
        :param preserve_package_names: When true (default) declared packages are kept identical and only files move
            (§6.2 step 5). When false the new package for each moved file is computed from the directory mapping (its
            relative directory beneath ``target_source_root``) and the project-wide package-rename logic runs, so
            package declarations, imports, fully-qualified references, ``module-info`` directives, and resource
            references are rewritten and validated (§6.2 step 6).
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation. This flag cannot weaken the apply safety gate: post-apply validation with
            rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project disables
            ``java_refactor.validate_before_apply``.
        """
        packages = self._parse_packages(packages_json)
        result = self._get_manager().move_source_root(
            source_root,
            target_source_root,
            packages=packages,
            include_subpackages=include_subpackages,
            rewrite_build_files=rewrite_build_files,
            preserve_package_names=preserve_package_names,
            apply=not self._resolve_preview(preview),
            validate=validate,
        )
        return self._finalize_result(result)

    @staticmethod
    def _parse_packages(packages_json: str) -> list[str]:
        """Parses the ``packages_json`` tool argument into a list of dotted package names.

        Accepts a JSON array literal (``["a.b","c.d"]``) or a plain comma-separated list (``a.b, c.d``); an empty or
        blank value means "all packages under the source root".
        """
        import json as _json

        text = (packages_json or "").strip()
        if not text or text == "[]":
            return []
        try:
            parsed = _json.loads(text)
        except _json.JSONDecodeError:
            return [item.strip() for item in text.split(",") if item.strip()]
        if isinstance(parsed, list):
            return [str(item).strip() for item in parsed if str(item).strip()]
        return [str(parsed).strip()] if str(parsed).strip() else []


class JavaPropagateSafeDeleteTool(_JavaRefactorToolBase):
    """Previews or applies a javac-validated propagating safe delete of dead Java types (V3 ``propagateSafeDelete``)."""

    def apply(
        self,
        seeds: str = "",
        cascade_depth: int | None = None,
        delete_private_only: bool = True,
        include_tests: bool = False,
        include_resources: bool = True,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Delete a set of seed Java types and every type they transitively orphan, validated by a real javac diagnostic delta.

        Starting from ``seeds``, the compiler-backed planner builds a whole-project reachability graph and returns a
        graph-shaped ``deletePlan {requested, cascade, blocked}``: ``requested`` echoes the resolved roots, ``cascade``
        lists every symbol that becomes deletable once its only referrers are themselves deleted (a bounded fixpoint),
        and ``blocked`` names each root or symbol that is kept — an unresolvable root, a public-API / framework boundary
        root, or a symbol a live referrer still needs — each with a reason. The emitted edit is a transactional
        whole-file-delete with an ``oldSha256`` precondition on every removed file (an empty edit when nothing is safely
        deletable). The edit is routed through the central javac validation bridge: it is staged and compiled with a real
        before/after diagnostic delta, so an accepted result carries ``diagnosticDeltaValidated: true`` and is REFUSED
        (``new_compiler_errors``) if it would leave any newly-introduced compiler error. An empty seed list is the only
        planner-level refusal (``no_roots``); unknown and boundary roots are ACCEPTED outcomes surfaced in
        ``deletePlan.blocked`` that write nothing.

        :param seeds: Fully-qualified seed type name(s) to delete, as a JSON array literal (``["com.acme.A","com.acme.B"]``)
            or a plain comma-separated list (``com.acme.A, com.acme.B``).
        :param cascade_depth: Optional bound on the cascade fixpoint depth; when omitted the analyzer's default applies.
        :param delete_private_only: When ``True`` (default) public/protected API is kept as a cascade root and is never
            auto-deleted (such symbols are reported in ``deletePlan.blocked``); pass ``False`` to allow cascading into
            public symbols (a public-API boundary warning is emitted per deleted public symbol). This is the sole
            public-API control on the propagate path. Forwarded as ``deletePrivateOnly`` to the sidecar planner.
        :param include_tests: When ``True`` the reachability graph includes test source sets so test-only symbols
            can cascade. Forwarded as ``includeTests`` to the sidecar planner.
        :param include_resources: When ``True`` (default) ``META-INF/services`` provider lines naming deleted classes
            are pruned from resource files. Forwarded as ``includeResources`` to the sidecar planner.
        :param preview: When true only the planned (javac-validated) deletion is returned; when false it is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation (the before/after diagnostic delta). This flag cannot weaken the apply safety gate:
            post-apply validation with rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless
            the project disables ``java_refactor.validate_before_apply``.
        :param allow_review_required: Uniform risk-policy control. When ``False`` (default) an edit the engine
            classifies REVIEW_REQUIRED (it crosses a public-API/framework/resource boundary or relies on a heuristic a
            human should confirm) is blocked on apply and writes nothing; set ``True`` to apply it after review. SAFE
            edits always apply and REFUSED results never apply, regardless of this flag.
        """
        seed_list = self._parse_seeds(seeds)
        result = self._get_manager().propagate_safe_delete(
            seed_list,
            cascade_depth=cascade_depth,
            delete_private_only=delete_private_only,
            include_tests=include_tests,
            include_resources=include_resources,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)

    @staticmethod
    def _parse_seeds(seeds: str) -> list[Any]:
        """Parses the ``seeds`` tool argument into a list of deletion roots (B08).

        Accepts three interchangeable seed forms in one JSON array literal (or a plain comma-separated list of the
        string forms):

        * a fully-qualified type name STRING (``"com.acme.A"``) — the backward-compatible alias,
        * a canonical symbol-key STRING (e.g. ``"com.acme.A#method(int)"``) — forwarded verbatim, and
        * a structured POSITION ROOT object ``{"relativePath": "...", "line": N, "column": M}`` — forwarded as a dict so
          the sidecar resolves the symbol at that source position.

        A JSON array may MIX these (``["com.acme.A", {"relativePath": "src/B.java", "line": 3, "column": 9}]``). String
        items are trimmed; dict items are validated to carry ``relativePath`` + ``line`` (``column`` optional) and passed
        through unchanged. A plain comma-separated value yields string seeds only. An empty/blank value yields an empty
        list (which the planner refuses with ``no_roots``); a malformed dict seed raises ``ValueError``.
        """
        import json as _json

        text = (seeds or "").strip()
        if not text or text == "[]":
            return []
        try:
            parsed = _json.loads(text)
        except _json.JSONDecodeError:
            return [item.strip() for item in text.split(",") if item.strip()]

        def _coerce(item: Any) -> Any:
            if isinstance(item, dict):
                if "relativePath" not in item or "line" not in item:
                    raise ValueError(
                        "A structured seed root must carry 'relativePath' and 'line' (and optional 'column'); "
                        f"got {item!r}."
                    )
                root: dict[str, Any] = {"relativePath": item["relativePath"], "line": item["line"]}
                if item.get("column") is not None:
                    root["column"] = item["column"]
                return root
            return str(item).strip()

        items = parsed if isinstance(parsed, list) else [parsed]
        coerced = [_coerce(item) for item in items]
        return [item for item in coerced if isinstance(item, dict) or item]


class JavaFindDeadCodeTool(_JavaRefactorToolBase):
    """Reports Java types unreachable from the public-API boundary, confidence-ranked (V3 ``findDeadCode``, READ-ONLY)."""

    def apply(
        self,
        min_confidence: str | None = None,
        scope: str | None = None,
        include_tests: bool = False,
        public_api_policy: str = "keep",
        max_answer_chars: int = -1,
    ) -> str:
        """
        Scan the project for dead Java types (unreachable from the public-API boundary), confidence-ranked. READ-ONLY.

        This is a pure analysis: it produces NO edit and writes nothing, so it has no preview/apply mode and runs no
        javac (there is nothing to validate). It returns ``deadCodeCandidates`` — a list of ``{symbol, confidence,
        reason}`` entries — plus ``stats {candidates, high, low}``. Each candidate is ranked HIGH (a non-public
        declaration with no incoming semantic references) or LOW (a public declaration, so possibly external API), and
        ``reason`` explains the classification. Feed a candidate's ``symbol`` key into ``propagate_safe_delete`` to
        actually (and javac-validatedly) remove it.

        :param min_confidence: Optional floor (``"high"``, ``"medium"`` or ``"low"``); candidates ranked below it are
            dropped (the scan itself only ever emits HIGH or LOW).
        :param scope: Optional package subtree to restrict the scan to (e.g. ``"com.acme.app"``); the default
            ``"project"``/``None`` scans the whole project. Applied semantically inside the sidecar against each
            candidate's javac-resolved owner-type FQN (package-segment-aware), not as a text post-filter.
        :param include_tests: Include test source sets in the reachability graph (default false).
        :param public_api_policy: one of ``"keep"`` (public/protected API is an entry point and such symbols are never
            reported, the default), ``"warn"`` (unreferenced public/protected symbols ARE reported as candidates with a
            public-API-boundary warning), or ``"allow"`` (public-API status is ignored, such symbols are treated like
            internal ones). The legacy value ``"report"`` is accepted as an alias for ``"warn"``.
        :param max_answer_chars: If the rendered candidate report exceeds this length the call errors instead of
            returning it (``-1`` uses the configured default); narrow the ``scope`` or raise ``min_confidence`` to fit.
        """
        result = self._get_manager().find_dead_code(
            min_confidence=min_confidence,
            scope=scope,
            include_tests=include_tests,
            public_api_policy=public_api_policy,
        )
        return self._limit_length(self._finalize_result(result), max_answer_chars)


def _parse_member_names(members: str) -> list[str]:
    """Parses the ``members`` tool argument into a list of field/method names.

    Accepts a JSON array literal (``["total","addToTotal"]``) or a plain comma-separated list (``total, addToTotal``);
    an empty or blank value yields an empty list (which the extract planners refuse with ``no_members``).
    """
    import json as _json

    text = (members or "").strip()
    if not text or text == "[]":
        return []
    try:
        parsed = _json.loads(text)
    except _json.JSONDecodeError:
        return [item.strip() for item in text.split(",") if item.strip()]
    if isinstance(parsed, list):
        return [str(item).strip() for item in parsed if str(item).strip()]
    return [str(parsed).strip()] if str(parsed).strip() else []


class JavaExtractClassTool(_JavaRefactorToolBase):
    """Previews or applies a javac-validated extract-class refactoring (V3 ``extractClass``)."""

    def apply(
        self,
        name_path: str = "",
        relative_path: str = "",
        new_class_name: str = "",
        members: str = "",
        target_package: str | None = None,
        leave_delegate_methods: bool = True,
        update_usages: bool = False,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Move a cohesive cluster of fields/methods out of a Java type into a new helper class, validated by a real javac delta.

        The selected ``members`` are relocated from the single top-level type declared in ``relative_path`` into a new
        ``new_class_name`` held behind a delegate field; by default (``leave_delegate_methods``) forwarding methods are
        left on the original so existing callers keep compiling. Each moved method's dependency closure is CLASSIFIED
        rather than blanket-refused: a selected dependency moves with the cluster; a retained source field a moved method
        reads is passed as a constructor parameter into the new class; a retained source method a moved method calls is
        reached through an injected back-reference to the source; only genuinely unrepresentable cases are refused. When
        ``leave_delegate_methods=False`` and ``update_usages=True``, external call sites of a removed method are rewritten
        through a generated public delegate accessor instead of being refused. The planned edit is routed through the
        central javac validation bridge: it is staged and compiled with a real before/after diagnostic delta, so an
        accepted result carries ``diagnosticDeltaValidated: true`` and is REFUSED (``new_compiler_errors``) if it would
        leave any newly-introduced compiler error. The planner refuses only the genuinely-unrepresentable cases
        (``no_members``, ``member_not_found``, ``source_type_not_found``, ``extract_class_static_field``,
        ``extract_class_static_method``, ``extract_class_native_method``, ``extract_class_abstract_method``,
        ``extract_class_uses_super``, ``extract_class_synchronized_receiver``, ``extract_class_source_type_parameter``,
        ``extract_class_unanalyzable_method``, ``extract_class_public_api_without_delegates``,
        ``extract_class_external_usage`` (only when ``update_usages`` is false or the usage form cannot be rewritten),
        ``extract_class_no_constructor_to_inject``, ``extract_class_constructor_unanalyzable``,
        ``extract_class_multiple_constructors``,
        ``extract_class_constructor_init_not_simple``, ``extract_class_field_assigned_multiple_times``,
        ``extract_class_field_not_constructor_assigned``).

        :param name_path: Optional Serena name path of the source type within ``relative_path`` (e.g. ``Cart``). When
            given it is resolved via the language server as an identity guard (raises on a miss/ambiguity); the sidecar
            resolves the file's top-level type regardless.
        :param relative_path: Project-relative path of the ``.java`` file whose top-level type is extracted from
            (e.g. ``src/main/java/com/acme/app/Cart.java``).
        :param new_class_name: Simple name of the new helper class to create (e.g. ``Totals``).
        :param members: Field/method selector(s) to move, as a JSON array literal
            (``["field:total","method:addToTotal(int)"]``) or a plain comma-separated list. Each selector must be
            ``field:<name>`` or ``method:<name>(<types>)``.
        :param target_package: Optional dotted package for the new helper class; when omitted it is created in the
            original type's package.
        :param leave_delegate_methods: When true (default) forwarding methods are left on the original type; when false
            they are dropped.
        :param update_usages: Only meaningful with ``leave_delegate_methods=False``. When true, external call sites of a
            removed method are rewritten to go through a generated public delegate accessor instead of refusing with
            ``extract_class_external_usage``. Defaults to false (preserve the conservative refusal).
        :param preview: When true only the planned (javac-validated) edit is returned; when false it is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent).
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged in-memory
            javac validation (the before/after diagnostic delta). This flag cannot weaken the apply safety gate: post-apply
            validation with rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project
            disables ``java_refactor.validate_before_apply``.
        """
        if relative_path and name_path:
            # Verify the named symbol resolves within relative_path (raises ValueError on a miss/ambiguity); the sidecar
            # resolves the file's primary type, so this resolution is used only as an identity guard.
            self._resolve_target(relative_path, name_path, None, None)
        result = self._get_manager().extract_class(
            relative_path,
            new_class_name,
            _parse_member_names(members),
            target_package=target_package,
            leave_delegate_methods=leave_delegate_methods,
            update_usages=update_usages,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)


class JavaExtractSuperclassTool(_JavaRefactorToolBase):
    """Previews or applies a javac-validated extract-superclass refactoring (V3 ``extractSuperclass``)."""

    def apply(
        self,
        name_path: str = "",
        relative_path: str = "",
        superclass_name: str = "",
        members: str = "",
        classes: str = "",
        target_package: str | None = None,
        make_abstract: bool = False,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Pull a set of members up from one or more sibling types into a new shared superclass, validated by a real javac delta.

        The selected ``members`` are pulled out of every selected type into a new ``superclass_name`` that each of those
        types then ``extends`` (multi-sibling pull-up: the members must be congruent across all selected types); pulled
        ``private`` members are widened to ``protected`` (surfaced as a warning). The source type is identified by
        ``name_path`` within ``relative_path`` (the Serena symbol targeting used by the rest of the Java refactor tools);
        additional sibling types may be listed via ``classes``. Unlike the pure-Python extract unit path, the planned
        edit is routed through the central javac validation bridge: it is staged and compiled with a real before/after
        diagnostic delta, so an accepted result carries ``diagnosticDeltaValidated: true`` and is REFUSED
        (``new_compiler_errors``) if it would leave any newly-introduced compiler error. The planner itself refuses
        conservatively (``extract_superclass_existing_superclass``, ``extract_superclass_member_not_common``,
        ``extract_superclass_private_member``, ``extract_superclass_abstract_member``,
        ``extract_superclass_constructor_with_existing_super``, ``no_members``, plus the member-level guards shared with
        extract-class). When the extracted superclass would carry no state and only abstract method declarations, the
        accepted result also surfaces an ``interface_alternative_suggested`` warning.

        :param name_path: Serena name path of the source type within ``relative_path`` (e.g. ``OnlineOrderHandler``).
            Resolved via the language server to verify the symbol exists; the sidecar resolves each file's top-level type.
            Optional when ``relative_path`` (and/or ``classes``) already pins the source file(s).
        :param relative_path: Project-relative path of the ``.java`` file whose top-level class is the (primary) source
            (e.g. ``src/main/java/com/acme/app/OnlineOrderHandler.java``), NOT a fully-qualified class name.
        :param superclass_name: Simple name of the new superclass to create (e.g. ``BaseAccount``).
        :param members: Field/method selector(s) to pull up, as a JSON array literal
            (``["field:balance","method:deposit(int)"]``) or a plain comma-separated list. Each selector must be
            ``field:<name>`` or ``method:<name>(<types>)``.
        :param classes: Optional ADDITIONAL sibling ``.java`` file path(s) (beyond ``relative_path``) whose top-level
            classes share the same members, as a JSON array literal
            (``["src/main/java/com/acme/StoreOrderHandler.java"]``) or a plain comma-separated list. Each entry MUST be a
            project-relative file path, NOT a fully-qualified class name. When ``relative_path`` is omitted, ``classes``
            alone provides the source type(s) (backward-compatible form).
        :param target_package: Optional dotted package for the new superclass; when omitted it is created in the
            (shared) package of the listed types.
        :param make_abstract: When true, each hoisted METHOD becomes an ``abstract`` declaration in the generated
            superclass while every subclass keeps its concrete override (annotated ``@Override``); fields are always
            pulled up wholesale regardless. When false (default) methods are moved wholesale into the superclass.
        :param preview: When true only the planned (javac-validated) edit is returned; when false it is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent).
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged in-memory
            javac validation (the before/after diagnostic delta). This flag cannot weaken the apply safety gate: post-apply
            validation with rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project
            disables ``java_refactor.validate_before_apply``.
        """
        class_paths: list[str] = []
        if relative_path:
            class_paths.append(relative_path)
            if name_path:
                # Verify the named symbol resolves within relative_path (raises ValueError on a miss/ambiguity); the
                # sidecar resolves each file's primary type, so we use the resolution only as an identity guard.
                self._resolve_target(relative_path, name_path, None, None)
        class_paths.extend(path for path in _parse_member_names(classes) if path not in class_paths)
        result = self._get_manager().extract_superclass(
            class_paths,
            superclass_name,
            _parse_member_names(members),
            target_package=target_package,
            make_abstract=make_abstract,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)


class JavaReplaceInheritanceWithDelegationTool(_JavaRefactorToolBase):
    """Previews or applies a javac-validated replace-inheritance-with-delegation refactoring (V3 ``replaceInheritanceWithDelegation``)."""

    def apply(
        self,
        relative_path: str = "",
        members: str = "",
        delegate_field_name: str | None = None,
        superclass_fqn: str | None = None,
        confirm_public_api_change: bool = False,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Rewrite ``class C extends P`` into composition (a delegate field + forwarders), validated by a real javac delta.

        The former superclass of the top-level type declared in ``relative_path`` is held behind a
        ``delegate_field_name`` field; inherited public API is re-exposed through forwarding methods (whose signatures use
        simple type names with imports added), ``super`` / inherited-method calls are redirected through the delegate, and
        ``super(...)`` constructor calls become delegate construction. A co-located ``implements`` clause is PRESERVED:
        only the ``extends`` relationship is severed. Unlike the pure-Python delegation unit path, the planned edit is
        routed through the central javac validation bridge: it is staged and compiled with a real before/after diagnostic
        delta, so an accepted result carries ``diagnosticDeltaValidated: true`` and is REFUSED (``new_compiler_errors``)
        if it would leave any newly-introduced compiler error. The planner itself refuses conservatively
        (``replace_inheritance_no_superclass``, ``replace_inheritance_sealed_superclass``,
        ``replace_inheritance_generic_superclass``, ``replace_inheritance_generic_subclass``,
        ``replace_inheritance_base_constructor_args``, ``replace_inheritance_protected_member_dependency``,
        ``replace_inheritance_public_api_change``).

        :param relative_path: Project-relative path of the ``.java`` file whose top-level subclass is converted
            (e.g. ``src/main/java/com/acme/app/Dog.java``).
        :param members: Optional inherited member name(s) to restrict the forwarders to, as a JSON array literal
            (``["bark","sit"]``) or a plain comma-separated list; empty (the default) re-exposes the full inherited API.
        :param delegate_field_name: Optional name for the synthesised delegate field holding the former superclass
            instance; when omitted the planner derives a default.
        :param superclass_fqn: Optional fully-qualified name of the expected direct superclass
            (e.g. ``com.acme.animals.Animal``). When provided, it is forwarded to the sidecar as ``superclassFqn``;
            the planner verifies it matches the javac-resolved direct superclass and refuses with
            ``replace_inheritance_superclass_mismatch`` on a mismatch.
        :param confirm_public_api_change: When true the planner is allowed to drop the supertype from the converted
            class's public API; when false (the default) it refuses with ``replace_inheritance_public_api_change``.
        :param preview: When true only the planned (javac-validated) edit is returned; when false it is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent).
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged in-memory
            javac validation (the before/after diagnostic delta). This flag cannot weaken the apply safety gate: post-apply
            validation with rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project
            disables ``java_refactor.validate_before_apply``.
        """
        member_names = _parse_member_names(members)
        result = self._get_manager().replace_inheritance_with_delegation(
            relative_path,
            members=member_names or None,
            delegate_field_name=delegate_field_name,
            superclass_fqn=superclass_fqn,
            confirm_public_api_change=confirm_public_api_change,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)


class JavaDeepInlineMethodTool(_JavaRefactorToolBase):
    """Previews or applies a javac-validated deep-inline-method refactoring (V3 ``deepInlineMethod``)."""

    def apply(
        self,
        relative_path: str = "",
        line: int = 0,
        column: int | None = None,
        method_name: str | None = None,
        delete_method: bool = False,
        max_call_sites: int | None = None,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Replace every call to a private Java method with its body and (optionally) delete it, validated by a real javac delta.

        The private, non-overridden method is located in ``relative_path`` either by its declaration ``line`` (optionally
        disambiguated by ``column``) or by an unambiguous ``method_name``; each call to it is replaced with the method's
        body — expression-mode for single-``return`` methods, block-mode for straight-line ``void`` methods — and, when
        ``delete_method`` is true, the now-unused declaration is removed. Unlike the pure-Python inline unit path, the
        planned edit is routed through the central javac validation bridge: it is staged and compiled with a real
        before/after diagnostic delta, so an accepted result carries ``diagnosticDeltaValidated: true`` and is REFUSED
        (``new_compiler_errors``) if it would leave any newly-introduced compiler error. The planner itself refuses
        conservatively (``inline_type_not_found``, ``inline_method_not_found``, ``inline_overloaded``,
        ``not_private``, ``inline_no_body``, ``inline_generic_method``, ``inline_loop_hazard``,
        ``inline_yield_hazard``, ``inline_super_hazard``, ``inline_early_return_hazard``,
        ``inline_checked_exception_hazard``, ``inline_expression_context_hazard``, ``inline_complex_body_hazard``,
        ``inline_qualified_call_hazard``, ``inline_recursion_hazard``, ``inline_arg_duplication_hazard``,
        ``inline_arity_mismatch``, ``inline_no_call_sites``).

        :param relative_path: Project-relative path of the ``.java`` file declaring the method
            (e.g. ``src/main/java/com/acme/app/Calc.java``).
        :param line: 1-based line of the private method's declaration to inline (used when ``method_name`` is omitted).
        :param column: Optional 1-based column to disambiguate when the declaration shares ``line`` with other code.
        :param method_name: Optional simple name of the private method to inline (e.g. ``square``) instead of a
            ``line``/``column`` locator. It must be unambiguous (an overloaded name is refused with ``inline_overloaded``).
        :param delete_method: By default (false) the inlined method is retained; pass ``delete_method=True`` to remove the
            now-unused declaration after inlining.
        :param max_call_sites: Optional cap on how many call sites may be inlined in a single operation. When the found
            call-site count exceeds the effective limit the operation is REFUSED with ``deep_inline_too_many_call_sites``
            rather than rewriting a large blast radius. When omitted, the project's configured
            ``java_refactor.v3.inline.max_call_sites`` limit applies (default 25); pass a larger value to opt in to a
            larger inline, or a smaller value to tighten the cap for this call only.
        :param preview: When true only the planned (javac-validated) edit is returned; when false it is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent).
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged in-memory
            javac validation (the before/after diagnostic delta). This flag cannot weaken the apply safety gate: post-apply
            validation with rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project
            disables ``java_refactor.validate_before_apply``.
        """
        result = self._get_manager().deep_inline_method(
            relative_path,
            line,
            column=column,
            method_name=method_name,
            delete_method=delete_method,
            max_call_sites=max_call_sites,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)


class JavaConvertAnonymousToLambdaTool(_JavaRefactorToolBase):
    """Previews or applies a javac-validated anonymous-class-to-lambda conversion (V3 ``convertAnonymousToLambda``)."""

    def apply(
        self,
        relative_path: str = "",
        line: int = 0,
        column: int | None = None,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Rewrite an anonymous functional-interface instance into an equivalent lambda, validated by a real javac delta.

        The anonymous-class instance located at ``relative_path``:``line`` (optionally disambiguated by ``column``) is
        rewritten into a lambda when it implements a single-abstract-method interface, declares exactly one method, holds
        no state, and references neither ``this`` nor ``super``. Unlike the pure-Python conversion unit path, the planned
        edit is routed through the central javac validation bridge: it is staged and compiled with a real before/after
        diagnostic delta, so an accepted result carries ``diagnosticDeltaValidated: true`` and is REFUSED
        (``new_compiler_errors``) if it would leave any newly-introduced compiler error. The planner itself refuses
        conservatively (``anon_type_not_found``, ``anon_not_found``, ``anon_not_functional_interface``,
        ``anon_multiple_methods``, ``anon_declares_field``, ``anon_this_reference``, ``anon_super_reference``,
        ``anon_extends_class``).

        :param relative_path: Project-relative path of the ``.java`` file declaring the anonymous instance
            (e.g. ``src/main/java/com/acme/app/Main.java``).
        :param line: 1-based line of the ``new <Interface>() { ... }`` anonymous-class expression to convert.
        :param column: Optional 1-based column to disambiguate when more than one anonymous instance begins on ``line``.
        :param preview: When true only the planned (javac-validated) edit is returned; when false it is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent).
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged in-memory
            javac validation (the before/after diagnostic delta). This flag cannot weaken the apply safety gate: post-apply
            validation with rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project
            disables ``java_refactor.validate_before_apply``.
        """
        result = self._get_manager().convert_anonymous_to_lambda(
            relative_path,
            line,
            column=column,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)


class JavaConvertLambdaToMethodReferenceTool(_JavaRefactorToolBase):
    """Previews or applies a javac-validated lambda-to-method-reference conversion (V3 ``convertLambdaToMethodReference``)."""

    def apply(
        self,
        relative_path: str = "",
        line: int = 0,
        column: int | None = None,
        preview: bool | None = None,
        validate: bool = True,
        allow_review_required: bool = False,
    ) -> str:
        """
        Rewrite an eligible single-call lambda into a method reference, validated by a real javac delta.

        The lambda located at ``relative_path``:``line`` (optionally disambiguated by ``column``) is rewritten into a
        method reference (static, bound-instance, or constructor) when its body is a single call expression that forwards
        the lambda parameters straight through, unchanged and in order. Unlike the pure-Python conversion unit path, the
        planned edit is routed through the central javac validation bridge: it is staged and compiled with a real
        before/after diagnostic delta, so an accepted result carries ``diagnosticDeltaValidated: true`` and is REFUSED
        (``new_compiler_errors``) if it would leave any newly-introduced compiler error. The planner itself refuses
        conservatively (``lambda_type_not_found``, ``lambda_not_found``, ``lambda_not_single_call``,
        ``lambda_arg_transformed``, ``lambda_arg_reordered``, ``lambda_receiver_uses_param``, ``lambda_partial_args``,
        ``lambda_unsupported_shape``).

        :param relative_path: Project-relative path of the ``.java`` file declaring the lambda
            (e.g. ``src/main/java/com/acme/app/Main.java``).
        :param line: 1-based line of the lambda expression (``... -> ...``) to convert.
        :param column: Optional 1-based column to disambiguate when more than one lambda begins on ``line``.
        :param preview: When true only the planned (javac-validated) edit is returned; when false it is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent).
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged in-memory
            javac validation (the before/after diagnostic delta). This flag cannot weaken the apply safety gate: post-apply
            validation with rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project
            disables ``java_refactor.validate_before_apply``.
        """
        result = self._get_manager().convert_lambda_to_method_reference(
            relative_path,
            line,
            column=column,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_review_required=allow_review_required,
        )
        return self._finalize_result(result)


class JavaScanMigrationOpportunitiesTool(_JavaRefactorToolBase):
    """Reports the grouped migration opportunities a recipe matches across the project (V3 ``scanMigrationOpportunities``, READ-ONLY)."""

    def apply(
        self,
        recipe_name: str = "",
        recipe_document: str = "",
        scope: str = "project",
        max_answer_chars: int = -1,
    ) -> str:
        """
        Scan the project for the migration opportunities a recipe matches, grouped by rule / file / risk. READ-ONLY.

        Selects a recipe from EITHER a built-in ``recipe_name`` OR an inline ``recipe_document`` (a JSON or YAML recipe
        document), matches every rule across the project's Java sources, and classifies each occurrence (SAFE for
        unambiguous/qualified rewrites, REVIEW_REQUIRED for receiver- or semantics-dependent ones). This is a pure
        preview: it produces NO edit, writes nothing, and runs no javac (there is nothing to validate). Feed the same
        recipe selection into ``apply_refactor_recipe`` to compose and javac-validate the transactional migration edit. A
        parse error, an unknown built-in name, or an ambiguous selection (neither or both of the two parameters) is
        returned as a structured refusal.

        Built-in recipe ids: ``junit4-to-junit5-basic``, ``javax-to-jakarta-basic``,
        ``deprecated-guava-optional-to-java-optional``, ``thread-stop-suspend-destroy-removal``,
        ``date-calendar-to-java-time-basic``.

        :param recipe_name: Id of a built-in recipe to run (mutually exclusive with ``recipe_document``).
        :param recipe_document: Inline recipe document as a JSON or YAML string (mutually exclusive with ``recipe_name``).
            Must declare a top-level ``id`` and a non-empty ``rules`` list; each rule names a supported ``kind``
            (``replaceType``, ``replaceImport``, ``replaceAnnotation``, ``removeAnnotation``, ``addAnnotation``,
            ``replaceMethodCall``, ``replaceStaticMethodCall``, ``replaceConstructor``, ``replaceFieldAccess``) and
            carries that kind's required fields (e.g. a fully-qualified ``owner`` for call/constructor/field/annotation
            rules; ``oldType``/``newType`` for type/import/annotation rules; ``owner``+``newType`` for ``addAnnotation``,
            with an optional ``name`` to annotate a specific member and an optional ``replacement`` for the full
            annotation text). ``changeMethodSignature`` is accepted as a structural rule: it is parsed as a distinct
            ``SignatureChangeRule`` and routed to the compiler-backed change-signature operation (not degraded to a text
            template and not refused).
        :param scope: Restrict matching to a package subtree (e.g. ``"com.acme.app"``); the default ``"project"`` scans
            the whole project. Applied semantically inside the sidecar by each matched file's package.
        :param max_answer_chars: If the rendered report exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default); narrow the recipe or ``scope`` to fit.
        """
        result = self._get_manager().scan_migration_opportunities(
            recipe_name=recipe_name or None,
            recipe_document=recipe_document or None,
            scope=scope,
        )
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaApplyRefactorRecipeTool(_JavaRefactorToolBase):
    """Previews or applies a javac-validated migration recipe across the project (V3 ``applyRefactorRecipe``)."""

    def apply(
        self,
        recipe_name: str = "",
        recipe_document: str = "",
        recipe_json: str = "",
        allow_review_required: bool = False,
        preview: bool | None = None,
        validate: bool = True,
        scope: str = "project",
    ) -> str:
        """
        Apply an API-migration recipe across the project as a single transactional edit, validated by a real javac delta.

        Selects a recipe from EITHER a built-in ``recipe_name`` OR an inline ``recipe_document`` (a JSON or YAML recipe
        document), matches every rule across the project's Java sources, and composes a transactional edit for every
        matched occurrence. Unlike the read-only ``scan_migration_opportunities`` preview, the composed edit is routed
        through the central javac validation bridge: it is staged and compiled with a real before/after diagnostic delta,
        so an accepted result carries ``diagnosticDeltaValidated: true`` and is REFUSED (``new_compiler_errors``) if it
        would leave any newly-introduced compiler error. The grouped ``matches``/``groups`` preview and impact ``summary``
        accompany the validated delta. A recipe that matches nothing is refused (``recipe_no_matches``); a parse error, an
        unknown built-in name, or an ambiguous selection (neither or both of the two parameters) is returned as a
        structured refusal.

        Built-in recipe ids: ``junit4-to-junit5-basic``, ``javax-to-jakarta-basic``,
        ``deprecated-guava-optional-to-java-optional``, ``thread-stop-suspend-destroy-removal``,
        ``date-calendar-to-java-time-basic``.

        :param recipe_name: Id of a built-in recipe to run (mutually exclusive with the inline recipe document).
        :param recipe_document: Inline recipe document as a JSON or YAML string (mutually exclusive with ``recipe_name``).
            Must declare a top-level ``id`` and a non-empty ``rules`` list; each rule names a supported ``kind``
            (``replaceType``, ``replaceImport``, ``replaceAnnotation``, ``removeAnnotation``, ``addAnnotation``,
            ``replaceMethodCall``, ``replaceStaticMethodCall``, ``replaceConstructor``, ``replaceFieldAccess``) and
            carries that kind's required fields (e.g. a fully-qualified ``owner`` for call/constructor/field/annotation
            rules; ``oldType``/``newType`` for type/import/annotation rules; ``owner``+``newType`` for ``addAnnotation``,
            with an optional ``name`` to annotate a specific member and an optional ``replacement`` for the full
            annotation text). ``changeMethodSignature`` is accepted as a structural rule: it is parsed as a distinct
            ``SignatureChangeRule`` and routed to the compiler-backed change-signature operation (not degraded to a text
            template and not refused).
        :param recipe_json: Backward-compatible alias for ``recipe_document`` (an inline JSON/YAML recipe definition).
            Supply at most one of ``recipe_document``/``recipe_json``; both forms feed the same inline-recipe selector.
        :param allow_review_required: When false (the default) only matches the engine classifies SAFE are applied;
            matches classified REVIEW_REQUIRED (``needs_review``) that carry a concrete replacement are SKIPPED (and
            counted in the result's warnings). Set true to ALSO apply those REVIEW_REQUIRED matches — report-only
            findings with no replacement are never applied regardless. Forwarded to the sidecar as ``apply_needs_review``.
        :param preview: When true only the planned (javac-validated) edit is returned; when false it is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent).
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged in-memory
            javac validation (the before/after diagnostic delta). This flag cannot weaken the apply safety gate: post-apply
            validation with rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project
            disables ``java_refactor.validate_before_apply``.
        :param scope: Restrict matching to a package subtree (e.g. ``"com.acme.app"``); the default ``"project"`` applies
            across the whole project. Applied semantically inside the sidecar by each matched file's package.
        """
        inline_document = recipe_document or recipe_json
        result = self._get_manager().apply_refactor_recipe(
            recipe_name=recipe_name or None,
            recipe_document=inline_document or None,
            allow_review_required=allow_review_required,
            apply=not self._resolve_preview(preview),
            validate=validate,
            scope=scope,
        )
        return self._finalize_result(result)


class JavaImpactReportTool(_JavaRefactorToolBase):
    """Whole-repo impact report (Java/resources/API/tests/risk) for a composed transformation workspace (V3 ``impactReport``, READ-ONLY)."""

    def apply(
        self,
        workspace_id: str,
        include_tests: bool = True,
        include_resources: bool = True,
        max_answer_chars: int = -1,
    ) -> str:
        """
        Produce a whole-repo impact report for a composed transformation workspace. READ-ONLY.

        Composes the workspace's enrolled sessions into one plan (without staging or writing) and projects a five-section
        report over it: the Java files/types touched, the resource files and resource-wired types, the public-API
        boundary crossings, the impacted tests to re-run, and the overall risk with its reasons. This is a pure analysis:
        it produces NO edit, writes nothing, and runs no javac (there is nothing to validate). Use it to review the blast
        radius of a transformation workspace before applying it. Refuses on an unknown/terminal/empty/conflicting
        workspace with a structured refusal.

        :param workspace_id: Id of an open transformation workspace (from the transformation-workspace tools) to report on.
        :param include_tests: When False, omit the ``tests`` section from the returned report. This is a presentation
            projection only: the sidecar always computes the impacted-test facts and the risk roll-up always reflects
            them; this flag only trims the returned envelope. Defaults to True.
        :param include_resources: When False, omit the ``resources`` section from the returned report. Same presentation-
            only semantics as ``include_tests`` — the risk classification is unaffected. Defaults to True.
        :param max_answer_chars: Maximum length of the returned report text; if exceeded, an error message is returned
            instead. ``-1`` (default) uses the configured default limit.
        """
        result = self._get_manager().transformation_workspace_impact_report(
            workspace_id,
            include_tests=include_tests,
            include_resources=include_resources,
        )
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaTransformationGraphTool(_JavaRefactorToolBase):
    """Builds the whole-project V3 transformation graph (project/build/symbols/hierarchy/calls/resources/tests) (V3 ``transformationGraph``, READ-ONLY)."""

    def apply(self, max_answer_chars: int = -1) -> str:
        """
        Build (or serve the revision-cached) V3 transformation graph for the project. READ-ONLY.

        Returns the seven-section, revision-keyed graph computed from real javac facts: ``project`` (source roots,
        packages, types), ``build`` (Maven/Gradle/module-info structure), ``symbols`` (types/members), ``hierarchy``
        (type hierarchy), ``calls`` (call-graph edges), ``resources`` (resource/service-loader references), and
        ``tests`` (likely affected tests), plus a ``stats`` roll-up. The graph is content-addressed and incrementally
        invalidated, so repeated builds at the same project revision are served from cache. Produces NO edit and writes
        nothing. A disabled engine or a sidecar ``graph.build`` failure is returned as a structured refusal.

        :param max_answer_chars: If the rendered graph exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().transformation_graph()
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaResourceReferencesTool(_JavaRefactorToolBase):
    """Finds references to a type or package across scanned resources (XML/properties/YAML/JSON/META-INF/services) (V3 ``resourceProviders``, READ-ONLY)."""

    def apply(
        self,
        target: str,
        target_is_package: bool = False,
        kinds: list[str] | None = None,
        max_answer_chars: int = -1,
    ) -> str:
        """
        Find references to a fully-qualified type (or a package) in the project's scanned resources. READ-ONLY.

        Forwards to the sidecar's compiler-backed resource scanner: exact-class and package-prefix matches across
        XML, properties, YAML, JSON, and ``META-INF/services`` provider files, each with its offset, kind, confidence,
        and provider. Produces NO edit and writes nothing. An unresolved target or an unsupported resource kind is
        returned as a structured refusal.

        :param target: The fully-qualified class name (or package, when ``target_is_package`` is True) to search for.
        :param target_is_package: When True, ``target`` is treated as a package prefix rather than an exact class FQN.
        :param kinds: Optional list of resource kinds to restrict the scan to (e.g. ``["xml", "service_loader"]``); the
            default (``None``) scans every supported kind.
        :param max_answer_chars: If the rendered result exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().resource_find_references(
            target,
            target_is_package=target_is_package,
            kinds=kinds,
        )
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaPlanResourceEditsTool(_JavaRefactorToolBase):
    """Plans the confidence-partitioned resource rewrites/renames for a set of moved types/packages (V3 ``resourceProviders``, READ-ONLY plan)."""

    def apply(
        self,
        type_fqn_map: dict[str, str] | None = None,
        package_map: dict[str, str] | None = None,
        rewrite_exact_class_names: bool = True,
        rewrite_package_prefixes: bool = False,
        apply_medium_confidence: bool = False,
        max_answer_chars: int = -1,
    ) -> str:
        """
        Plan the SAFE resource rewrites and file renames implied by moving a set of types/packages. READ-ONLY (plan only).

        Forwards to the sidecar's compiler-backed resource planner: a confidence-partitioned edit plan
        (``autoApply`` / ``preview`` / ``reviewOnly`` rewrites plus ``fileRenames``) for the supplied moved-type and
        moved-package maps. It plans only — it stages nothing and writes nothing. An empty rename set is returned as a
        structured refusal.

        :param type_fqn_map: Mapping of old fully-qualified class name -> new fully-qualified class name for moved types.
        :param package_map: Mapping of old package -> new package for moved packages.
        :param rewrite_exact_class_names: When True (default), plan rewrites of exact class-name references.
        :param rewrite_package_prefixes: When True, also plan rewrites of package-prefix references (default False).
        :param apply_medium_confidence: When True, promote medium-confidence matches into the auto-apply partition
            instead of leaving them for preview (default False).
        :param max_answer_chars: If the rendered plan exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().resource_plan_edits(
            type_fqn_map=type_fqn_map,
            package_map=package_map,
            rewrite_exact_class_names=rewrite_exact_class_names,
            rewrite_package_prefixes=rewrite_package_prefixes,
            apply_medium_confidence=apply_medium_confidence,
        )
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaFrameworkDetectTool(_JavaRefactorToolBase):
    """Detects which known frameworks are present in the project, by applied annotations (V3 ``frameworkDetect``, READ-ONLY)."""

    def apply(self, max_answer_chars: int = -1) -> str:
        """
        Detect which known frameworks are present in the project. READ-ONLY.

        Forwards to the sidecar's compiler-backed framework detector: one entry per known framework with whether it is
        ``detected`` and the ``evidence`` annotations found. Detection is by applied annotations resolved through the
        compiler, not by package-name heuristic. Produces NO edit and writes nothing. A disabled engine is returned as
        a structured refusal.

        :param max_answer_chars: If the rendered result exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().framework_detect()
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaFrameworkReferencesTool(_JavaRefactorToolBase):
    """Finds framework-significant references to a Java type using framework SPI facts (V3 ``frameworkReferences``, READ-ONLY)."""

    def apply(self, target: str, max_answer_chars: int = -1) -> str:
        """
        Find framework-significant references to ``target``. READ-ONLY.

        Parameters
        ----------
        target:
            Fully-qualified Java class name to resolve through framework reference providers.
        max_answer_chars:
            Maximum length of the returned JSON string after standard tool finalization/validation
            (``-1`` uses the configured default).
        """
        result = self._get_manager().framework_find_references(target)
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaCreateTransformationWorkspaceTool(_JavaRefactorToolBase):
    """Opens a new V3 transformation workspace that groups multiple operations under one revision-guarded unit (V3 ``transformationWorkspace``)."""

    def apply(self, max_answer_chars: int = -1) -> str:
        """
        Open a new transformation workspace and return its id and status summary.

        The workspace groups several compiler-backed operations into one revision-guarded, transactionally-applied unit.
        Enroll members with ``java_add_workspace_session`` (a V2 refactor operation) or ``java_add_workspace_operation``
        (a compute-only V3 operation), review the composed blast radius with ``java_preview_transformation_workspace`` or
        ``java_impact_report``, then commit atomically with ``java_apply_transformation_workspace`` (or discard with
        ``java_cancel_transformation_workspace``). The returned ``workspaceId`` is the handle every other workspace tool
        takes. Produces NO edit on its own. A disabled engine is returned as a structured refusal.

        :param max_answer_chars: If the rendered status exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().transformation_workspace_create()
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaAddWorkspaceSessionTool(_JavaRefactorToolBase):
    """Enrolls a V2 refactor operation as a member of an open transformation workspace (V3 ``transformationWorkspace``)."""

    def apply(
        self,
        workspace_id: str,
        operation: str,
        params: dict | None = None,
        validate: bool | None = None,
        max_answer_chars: int = -1,
    ) -> str:
        """
        Plan a V2 refactor operation and enroll it as a member of an open transformation workspace.

        The operation is planned (not applied) against the workspace's pinned project revision and its edit cached for
        composition; a member planned against a different revision than the workspace pins is refused so the workspace can
        only ever compose a single coherent revision. Produces NO edit on its own — members are committed together by
        ``java_apply_transformation_workspace``. A disabled engine, an unknown/terminal workspace, a sidecar-declined
        session, or a revision mismatch is returned as a structured refusal.

        :param workspace_id: Id of an open workspace (from ``java_create_transformation_workspace``).
        :param operation: The V2 refactor operation to plan and enroll (e.g. ``renameSymbol``, ``safeDelete``,
            ``changeSignature``, ``moveType`` — the same operation names the V2 session driver accepts).
        :param params: Operation-specific parameter object for the V2 session (the same payload the standalone V2 tool
            would send). Defaults to an empty object.
        :param validate: Optional preview-time validation toggle forwarded to the planned session; omit to use the
            project default. This cannot weaken the workspace apply safety gate.
        :param max_answer_chars: If the rendered status exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().transformation_workspace_add_session(
            workspace_id,
            operation,
            params or {},
            validate=validate,
        )
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaAddWorkspaceOperationTool(_JavaRefactorToolBase):
    """Enrolls a compute-only V3 operation as a member of an open transformation workspace (V3 ``transformationWorkspace``)."""

    def apply(
        self,
        workspace_id: str,
        operation: str,
        params: dict | None = None,
        max_answer_chars: int = -1,
    ) -> str:
        """
        Plan a compute-only V3 operation and enroll it as a member of an open transformation workspace.

        The V3 counterpart of ``java_add_workspace_session``: the operation is planned compute-only (nothing is applied
        or javac-validated yet) against the workspace's pinned project revision and its edit cached for composition; a
        member planned against a different revision than the workspace pins is refused. Produces NO edit on its own —
        members are committed together by ``java_apply_transformation_workspace``. A disabled engine, an unknown/terminal
        workspace, a sidecar-declined operation, or a revision mismatch is returned as a structured refusal.

        :param workspace_id: Id of an open workspace (from ``java_create_transformation_workspace``).
        :param operation: The V3 operation to plan and enroll (e.g. ``renamePackage``, ``movePackage``,
            ``extractClass`` — a compute-only V3 operation name).
        :param params: Operation-specific parameter object for the V3 operation (the same payload the standalone V3 tool
            would send). Defaults to an empty object.
        :param max_answer_chars: If the rendered status exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().transformation_workspace_add_operation(
            workspace_id,
            operation,
            params or {},
        )
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaPreviewTransformationWorkspaceTool(_JavaRefactorToolBase):
    """Composes and validates the member plan of a transformation workspace without writing anything (V3 ``transformationWorkspace``, READ-ONLY)."""

    def apply(self, workspace_id: str, max_answer_chars: int = -1) -> str:
        """
        Compose and validate the member plan of a transformation workspace without writing anything. READ-ONLY.

        Merges every enrolled member into one edit, revalidates each file-hash precondition, stages the merged edit
        in memory, and returns the aggregated stats, risk, and impact projection. A drifted, overlapping, or otherwise
        unsafe composition is refused HERE rather than at apply time, so this is the safe pre-flight before
        ``java_apply_transformation_workspace``. Produces NO edit and writes nothing. A disabled engine or an
        unknown/terminal workspace is returned as a structured refusal.

        :param workspace_id: Id of an open workspace (from ``java_create_transformation_workspace``) to preview.
        :param max_answer_chars: If the rendered preview exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().transformation_workspace_preview(workspace_id)
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaApplyTransformationWorkspaceTool(_JavaRefactorToolBase):
    """Composes and transactionally commits the member plan of a transformation workspace, all-or-nothing (V3 ``transformationWorkspace``)."""

    def apply(
        self,
        workspace_id: str,
        validate: bool | None = None,
        expected_project_revision: str | None = None,
        max_answer_chars: int = -1,
    ) -> str:
        """
        Compose and transactionally commit the member plan of a transformation workspace, all-or-nothing.

        Merges every enrolled member into one edit, stages and validates it, then commits it atomically and releases the
        member sessions; on any staging or commit failure the applier rolls back and nothing is written. Post-apply javac
        validation with rollback always runs; staged pre-commit validation runs unless the project disables
        ``java_refactor.validate_before_apply``. A disabled engine, an unknown/terminal workspace, a revision mismatch, or
        a composition/commit failure is returned as a structured refusal.

        :param workspace_id: Id of an open workspace (from ``java_create_transformation_workspace``) to commit.
        :param validate: Optional preview-time validation toggle; omit to use the project default. This cannot weaken the
            apply safety gate — post-apply validation with rollback always runs.
        :param expected_project_revision: Optional optimistic-concurrency guard. When supplied it must equal the revision
            the workspace pins (copy it verbatim from a workspace status's ``projectRevision``) or the apply is refused
            before any write; omit to skip the guard.
        :param max_answer_chars: If the rendered result exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().transformation_workspace_apply(
            workspace_id,
            validate=validate,
            expected_project_revision=expected_project_revision,
        )
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaCancelTransformationWorkspaceTool(_JavaRefactorToolBase):
    """Cancels every member of a transformation workspace and drops it from the registry (V3 ``transformationWorkspace``)."""

    def apply(self, workspace_id: str, max_answer_chars: int = -1) -> str:
        """
        Cancel every member of a transformation workspace and drop it from the registry.

        V2 member sessions are cancelled in the sidecar and compute-only V3 op members are discarded; nothing that was
        planned is applied. Produces NO edit and writes nothing. A disabled engine or an unknown/terminal workspace is
        returned as a structured refusal.

        :param workspace_id: Id of an open workspace (from ``java_create_transformation_workspace``) to cancel.
        :param max_answer_chars: If the rendered result exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().transformation_workspace_cancel(workspace_id)
        return self._limit_length(self._finalize_result(result), max_answer_chars)


class JavaListTransformationWorkspacesTool(_JavaRefactorToolBase):
    """Lists the live transformation workspaces with their status summaries (V3 ``transformationWorkspace``, READ-ONLY)."""

    def apply(self, max_answer_chars: int = -1) -> str:
        """
        List the live transformation workspaces with their status summaries. READ-ONLY.

        Reclaims expired/overflowing workspaces first, then returns one status summary per live workspace (its id,
        status, member/session count, and pinned project revision). Produces NO edit and writes nothing. A disabled
        engine is returned as a structured refusal.

        :param max_answer_chars: If the rendered list exceeds this length the call errors instead of returning it
            (``-1`` uses the configured default).
        """
        result = self._get_manager().transformation_workspace_list()
        return self._limit_length(self._finalize_result(result), max_answer_chars)


# The complete set of V3 transformation-engine Java refactoring tools. Tool visibility is gated on the active project's
# ``java_refactor.enabled`` flag and the sidecar capability registry, exactly like the V2 operation tools.
#
JAVA_REFACTOR_V3_TOOL_CLASSES = (
    JavaRenamePackageTool,
    JavaMovePackageTool,
    JavaMoveSourceRootTool,
    JavaPropagateSafeDeleteTool,
    JavaFindDeadCodeTool,
    JavaExtractClassTool,
    JavaExtractSuperclassTool,
    JavaReplaceInheritanceWithDelegationTool,
    JavaDeepInlineMethodTool,
    JavaConvertAnonymousToLambdaTool,
    JavaConvertLambdaToMethodReferenceTool,
    JavaScanMigrationOpportunitiesTool,
    JavaApplyRefactorRecipeTool,
    JavaImpactReportTool,
    JavaTransformationGraphTool,
    JavaResourceReferencesTool,
    JavaPlanResourceEditsTool,
    JavaFrameworkDetectTool,
    JavaFrameworkReferencesTool,
    JavaCreateTransformationWorkspaceTool,
    JavaAddWorkspaceSessionTool,
    JavaAddWorkspaceOperationTool,
    JavaPreviewTransformationWorkspaceTool,
    JavaApplyTransformationWorkspaceTool,
    JavaCancelTransformationWorkspaceTool,
    JavaListTransformationWorkspacesTool,
)


# V3 operation tools whose registration/status is negotiated against the sidecar capability registry.  The registry is
# intentionally complete: edit-emitting tools, read-only analytic tools, framework/resource SPI tools, and workspace
# lifecycle tools all carry a V3 capability operation so registration, status reporting, and dispatch refusals agree.
JAVA_REFACTOR_V3_CAPABILITY_TOOLS: dict[type, str] = {
    JavaRenamePackageTool: "renamePackage",
    JavaMovePackageTool: "movePackage",
    JavaMoveSourceRootTool: "moveSourceRoot",
    JavaPropagateSafeDeleteTool: "deletion.propagateSafeDelete",
    JavaFindDeadCodeTool: "deletion.findDeadCode",
    JavaExtractClassTool: "classRefactor.extractClass",
    JavaExtractSuperclassTool: "classRefactor.extractSuperclass",
    JavaReplaceInheritanceWithDelegationTool: "classRefactor.replaceInheritanceWithDelegation",
    JavaConvertAnonymousToLambdaTool: "conversions.anonymousToLambda",
    JavaConvertLambdaToMethodReferenceTool: "conversions.lambdaToMethodReference",
    JavaDeepInlineMethodTool: "inlineRefactor.deepInlineMethod",
    JavaScanMigrationOpportunitiesTool: "recipes.scanMigrationOpportunities",
    JavaApplyRefactorRecipeTool: "recipes.applyRecipe",
    JavaImpactReportTool: "impact.facts",
    JavaPlanResourceEditsTool: "resources.planEdits",
    JavaResourceReferencesTool: "resources.findReferences",
    JavaFrameworkDetectTool: "frameworks.detect",
    JavaFrameworkReferencesTool: "frameworks.findReferences",
    JavaCreateTransformationWorkspaceTool: "transformation.createWorkspace",
    JavaAddWorkspaceSessionTool: "transformation.addSession",
    JavaAddWorkspaceOperationTool: "transformation.addOperation",
    JavaPreviewTransformationWorkspaceTool: "transformation.preview",
    JavaApplyTransformationWorkspaceTool: "transformation.apply",
    JavaCancelTransformationWorkspaceTool: "transformation.cancel",
    JavaListTransformationWorkspacesTool: "transformation.list",
    JavaTransformationGraphTool: "graph.build",
}


def java_refactor_v3_capability_tool_operations() -> dict[str, str]:
    """Maps each capability-gated V3 tool name to its sidecar operation identifier."""
    return {cls.get_name_from_cls(): operation for cls, operation in JAVA_REFACTOR_V3_CAPABILITY_TOOLS.items()}


# Binds each acceptance-matrix operation to the registered V3 tool(s) that expose it at the Serena tool layer. This is
# the honest reachability contract behind the matrix: a matrix row only claims an operation that a registered tool
# actually reaches. ``resourceProviders`` is reached by two tools (resource find-references and resource plan-edits) and
# the workspace-lifecycle operation (``transformationWorkspace``) by its seven create/add/preview/apply/cancel/list
# lifecycle tools; every other operation by one.
V3_MATRIX_TOOL_BINDINGS: dict[str, tuple[type, ...]] = {
    "transformationWorkspace": (
        JavaCreateTransformationWorkspaceTool,
        JavaAddWorkspaceSessionTool,
        JavaAddWorkspaceOperationTool,
        JavaPreviewTransformationWorkspaceTool,
        JavaApplyTransformationWorkspaceTool,
        JavaCancelTransformationWorkspaceTool,
        JavaListTransformationWorkspacesTool,
    ),
    "transformationGraph": (JavaTransformationGraphTool,),
    "renamePackage": (JavaRenamePackageTool,),
    "movePackage": (JavaMovePackageTool,),
    "moveSourceRoot": (JavaMoveSourceRootTool,),
    "propagatingSafeDelete": (JavaPropagateSafeDeleteTool,),
    "deadCodeScan": (JavaFindDeadCodeTool,),
    "resourceProviders": (JavaResourceReferencesTool, JavaPlanResourceEditsTool),
    "frameworkDetect": (JavaFrameworkDetectTool,),
    "frameworkReferences": (JavaFrameworkReferencesTool,),
    "extractClass": (JavaExtractClassTool,),
    "extractSuperclass": (JavaExtractSuperclassTool,),
    "replaceInheritanceWithDelegation": (JavaReplaceInheritanceWithDelegationTool,),
    "deepInlineMethod": (JavaDeepInlineMethodTool,),
    "convertAnonymousToLambda": (JavaConvertAnonymousToLambdaTool,),
    "convertLambdaToMethodReference": (JavaConvertLambdaToMethodReferenceTool,),
    "scanMigrationOpportunities": (JavaScanMigrationOpportunitiesTool,),
    "applyRefactorRecipe": (JavaApplyRefactorRecipeTool,),
    "impactReport": (JavaImpactReportTool,),
}


def java_refactor_v3_non_capability_tool_names() -> list[str]:
    """Names of V3 tools that are NOT negotiated against the V3 capability registry.

    The V3 acceptance contract requires every public V3 capability/tool to participate in the same capability/status
    path.  This function remains for backward-compatible callers and should normally return an empty list.
    """
    capability_classes = set(JAVA_REFACTOR_V3_CAPABILITY_TOOLS)
    return [cls.get_name_from_cls() for cls in JAVA_REFACTOR_V3_TOOL_CLASSES if cls not in capability_classes]


__all__ = [
    "JAVA_REFACTOR_V3_CAPABILITY_TOOLS",
    "JAVA_REFACTOR_V3_TOOL_CLASSES",
    "V3_MATRIX_TOOL_BINDINGS",
    "JavaAddWorkspaceOperationTool",
    "JavaAddWorkspaceSessionTool",
    "JavaApplyRefactorRecipeTool",
    "JavaApplyTransformationWorkspaceTool",
    "JavaCancelTransformationWorkspaceTool",
    "JavaConvertAnonymousToLambdaTool",
    "JavaConvertLambdaToMethodReferenceTool",
    "JavaCreateTransformationWorkspaceTool",
    "JavaDeepInlineMethodTool",
    "JavaExtractClassTool",
    "JavaExtractSuperclassTool",
    "JavaFindDeadCodeTool",
    "JavaFrameworkDetectTool",
    "JavaFrameworkReferencesTool",
    "JavaImpactReportTool",
    "JavaListTransformationWorkspacesTool",
    "JavaMovePackageTool",
    "JavaMoveSourceRootTool",
    "JavaPlanResourceEditsTool",
    "JavaPreviewTransformationWorkspaceTool",
    "JavaPropagateSafeDeleteTool",
    "JavaRenamePackageTool",
    "JavaReplaceInheritanceWithDelegationTool",
    "JavaResourceReferencesTool",
    "JavaScanMigrationOpportunitiesTool",
    "JavaTransformationGraphTool",
    "java_refactor_v3_capability_tool_operations",
    "java_refactor_v3_non_capability_tool_names",
]
