"""Thin Python client for the sidecar ``recipes.*`` protocol (refactor-feature-plan-V3.md §14).

Phase 6 puts a semantic API-migration recipe engine in the Java sidecar. A recipe is a set of rules
(``replaceType`` / ``replaceMethodCall`` / ``replaceStaticMethodCall`` / ``replaceConstructor`` / ``replaceFieldAccess``
/ ``replaceImport`` / ``replaceAnnotation`` / ``removeAnnotation``) that match against javac-resolved symbols, never
textual guesses. Each match carries a §14.3 risk (``safe`` / ``needs_review``); migrations with no semantics-preserving
replacement are surfaced as report-only ``needs_review`` findings. Five built-in recipes ship by id (§14.4):
``junit4-to-junit5-basic``, ``javax-to-jakarta-basic``, ``deprecated-guava-optional-to-java-optional``,
``thread-stop-suspend-destroy-removal``, ``date-calendar-to-java-time-basic``.

This module is the Python side: a stateless wrapper over a live
:class:`~serena.java_refactor.client.JavaRefactorClient` that forwards each ``recipes.*`` request and returns the
sidecar's JSON result verbatim. ``scan_migration_opportunities`` is read-only (findings only); ``apply_recipe`` returns
a ``workspaceEdit`` the sidecar has already run through its before/after javac validator
(``diagnosticDeltaValidated: true``). The sidecar never writes files; the caller's transactional applier owns apply.
A refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``) drawn from the §14 code list
(``recipe_not_found``, ``malformed_recipe``, ``recipe_unknown_rule_kind``, ``recipe_unsupported_template``,
``recipe_unresolved_symbol``, ``recipe_no_matches``, ``recipe_overlapping_edits``).
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class RecipeEngineClient:
    """Forwards ``recipes.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def scan_migration_opportunities(
        self,
        *,
        recipe_id: str | None = None,
        recipe: dict[str, Any] | None = None,
        scope: str = "project",
    ) -> dict[str, Any]:
        """Scans the project for a recipe's matches (§14), returning report-only findings (no edits).

        Supply exactly one of ``recipe_id`` (a built-in id) or ``recipe`` (an inline §14.1 recipe object). The result
        carries a ``findings`` array (each with ``ruleId``/``ruleKind``/``risk``/``path``/``line``/offsets/``oldText``/
        ``newText``/``detail``) plus ``stats``. ``scope`` restricts matching to a package subtree (default ``"project"``
        = whole project; the sidecar filters by each matched file's package). Refused with ``recipe_not_found`` /
        ``malformed_recipe`` / ``recipe_unknown_rule_kind`` / ``recipe_unsupported_template`` when the recipe cannot be
        resolved or parsed.
        """
        params = self._recipe_params(recipe_id, recipe)
        params["scope"] = scope
        return self._client._request("recipes.scanMigrationOpportunities", {"params": params})

    def apply_recipe(
        self,
        *,
        recipe_id: str | None = None,
        recipe: dict[str, Any] | None = None,
        apply_needs_review: bool = False,
        validate: bool = True,
        scope: str = "project",
    ) -> dict[str, Any]:
        """Plans a recipe's edits (§14) as a javac-validated ``workspaceEdit``.

        Supply exactly one of ``recipe_id`` or ``recipe``. By default only ``safe`` matches are applied; set
        ``apply_needs_review`` to also apply ``needs_review`` matches that carry a replacement (report-only findings are
        never applied). ``validate`` runs the sidecar's before/after javac validation. ``scope`` restricts matching to a
        package subtree (default ``"project"`` = whole project; the sidecar filters by each matched file's package).
        Refused with ``recipe_no_matches`` (recipe resolved but matched nothing) or ``recipe_unresolved_symbol`` (every
        targeted symbol is absent from the project) in addition to the resolution/parse codes.
        """
        params = self._recipe_params(recipe_id, recipe)
        # The sidecar RecipeEngine reads this from the request fields under the snake_case key `apply_needs_review`
        # (recipes.* bypass the camelCase config field-mapping), so it MUST be sent in that exact form to take effect.
        params["apply_needs_review"] = apply_needs_review
        params["validate"] = validate
        params["scope"] = scope
        return self._client._request("recipes.applyRecipe", {"params": params})

    @staticmethod
    def _recipe_params(recipe_id: str | None, recipe: dict[str, Any] | None) -> dict[str, Any]:
        if (recipe_id is None) == (recipe is None):
            raise ValueError("Supply exactly one of 'recipe_id' or 'recipe'.")
        params: dict[str, Any] = {}
        if recipe_id is not None:
            params["recipeId"] = recipe_id
        if recipe is not None:
            params["recipe"] = recipe
        return params
