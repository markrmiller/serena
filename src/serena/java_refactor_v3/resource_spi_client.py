"""Thin Python client for the sidecar ``resources.*`` protocol (refactor-feature-plan-V3.md §15).

Phase 6 puts a resource-reference SPI in the Java sidecar: it locates references to a Java type or package inside
non-Java resource files (``META-INF/services`` provider lists, XML, ``.properties``/``.yml``/``.yaml``/``.json`` config,
and any other text file as a scan-only reflection candidate). Each reference carries a ``kind`` (e.g.
``SERVICE_LOADER_PROVIDER``, ``EXACT_CLASS_NAME``, ``PACKAGE_PREFIX``, ``REFLECTIVE_STRING_CANDIDATE``) and a
``confidence`` (``HIGH``/``MEDIUM``/``LOW``); matching is exact-class-only (no fuzzy/substring guessing).

The SPI has two halves. ``resources.findReferences`` is read-only — it surfaces every reference (including
low-confidence reflective candidates) for impact/safety analysis. ``resources.planEdits`` is the rewrite half: given the
moved-type/package maps for a rename or move, it plans only the SAFE in-place edits (exact-class HIGH, package-prefix
MEDIUM when enabled) and any ServiceLoader interface-file renames (§15.2); it never auto-edits reflective/free-text
matches. Each planned edit also carries a §18.4 ``disposition`` (``AUTO_APPLY``/``PREVIEW``/``REVIEW_ONLY``) and the
edits are partitioned into ``autoApply``/``preview`` arrays (HIGH auto-applies; MEDIUM previews unless
``applyMediumConfidence`` is set; LOW is returned review-only in ``reviewOnly``). This is the same unified engine the
package rename/move planners drive internally. This module is the Python
side: a stateless wrapper over a live :class:`~serena.java_refactor.client.JavaRefactorClient` that forwards the request
and returns the sidecar's JSON verbatim. A refusal carries ``accepted: false`` with a ``refusal`` object
(``code``/``message``): ``resource_target_unresolved`` (missing find target), ``unsupported_resource_kind`` (unknown
``kinds`` filter entry), or ``resource_rename_empty`` (planEdits with no ``typeFqnMap``/``packageMap``).
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class ResourceSpiClient:
    """Forwards ``resources.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def find_references(
        self,
        target: str,
        *,
        target_is_package: bool = False,
        kinds: list[str] | None = None,
    ) -> dict[str, Any]:
        """Finds references to ``target`` (a fully-qualified class, or a package when ``target_is_package``) in resources.

        ``kinds`` optionally restricts results to specific ``ResourceReferenceKind`` names. The result carries a
        ``references`` array (each with ``path``/offsets/``oldText``/``kind``/``confidence``/``provider``/``target``)
        plus ``stats`` and ``warnings``. Refused with ``resource_target_unresolved`` (empty ``target``) or
        ``unsupported_resource_kind`` (an unknown ``kinds`` entry).
        """
        params: dict[str, Any] = {
            "target": target,
            "targetIsPackage": target_is_package,
        }
        if kinds is not None:
            params["kinds"] = kinds
        return self._client._request("resources.findReferences", {"params": params})

    def plan_edits(
        self,
        *,
        type_fqn_map: dict[str, str] | None = None,
        package_map: dict[str, str] | None = None,
        rewrite_exact_class_names: bool = True,
        rewrite_package_prefixes: bool = False,
        apply_medium_confidence: bool = False,
        scan_xml: bool = True,
        scan_properties: bool = True,
        scan_yaml: bool = True,
        scan_json: bool = True,
        scan_service_loader: bool = True,
    ) -> dict[str, Any]:
        """Plans the SAFE resource rewrites and file renames for a set of moved types/packages.

        ``type_fqn_map`` maps each moved type's old fully-qualified name to its new one (rewritten exact-class, HIGH);
        ``package_map`` maps each moved package's old name to its new one (rewritten only when
        ``rewrite_package_prefixes`` is set, MEDIUM). At least one of the two maps must be non-empty.

        ``apply_medium_confidence`` controls the §18.4 confidence apply policy that the result reports: each edit carries
        a ``disposition`` (``AUTO_APPLY``/``PREVIEW``/``REVIEW_ONLY``) and the edits are partitioned into ``autoApply``
        and ``preview`` arrays. HIGH edits always auto-apply; MEDIUM edits preview unless ``apply_medium_confidence`` is
        set (then they auto-apply); LOW (reflective/free-text) matches are never edited and are returned in a separate
        ``reviewOnly`` array. The result also carries the full ``edits`` array (each with
        ``path``/offsets/``newText``/``kind``/``confidence``/``disposition``/``provider``), a ``fileRenames`` array
        (``from``/``to``/``provider``/``reason``), plus ``stats`` and ``warnings``. Refused with
        ``resource_rename_empty`` when both maps are empty, or ``malformed_resource_edit_map`` for non-string/blank
        map entries.
        """
        params: dict[str, Any] = {
            "typeFqnMap": dict(type_fqn_map or {}),
            "packageMap": dict(package_map or {}),
            "rewriteExactClassNames": rewrite_exact_class_names,
            "rewritePackagePrefixes": rewrite_package_prefixes,
            "applyMediumConfidence": apply_medium_confidence,
            "scanXml": scan_xml,
            "scanProperties": scan_properties,
            "scanYaml": scan_yaml,
            "scanJson": scan_json,
            "scanServiceLoader": scan_service_loader,
        }
        return self._client._request("resources.planEdits", {"params": params})
