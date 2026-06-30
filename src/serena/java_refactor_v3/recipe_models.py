"""Planned V3 recipe model module.

Recipe execution is implemented by :class:`RecipeEngineClient`; current recipe
payloads are JSON-compatible dictionaries owned by the sidecar.  These aliases
make the planned module importable while keeping the sidecar schema canonical.
"""

from typing import Any, TypeAlias

RecipeDocument: TypeAlias = dict[str, Any]
RecipeOperation: TypeAlias = dict[str, Any]
RecipeResult: TypeAlias = dict[str, Any]
MigrationOpportunity: TypeAlias = dict[str, Any]

__all__ = ["MigrationOpportunity", "RecipeDocument", "RecipeOperation", "RecipeResult"]
