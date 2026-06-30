"""Thin Python client for the sidecar ``classRefactor.*`` protocol (refactor-feature-plan-V3.md §8–§10).

Phase 4 puts three class-shape refactorings in the Java sidecar, where javac's ``Trees``/``Elements`` model is
authoritative for member resolution and the conservative refusal lists. This module is the Python side: a stateless
wrapper over a live :class:`~serena.java_refactor.client.JavaRefactorClient` that forwards each ``classRefactor.*``
request and returns the sidecar's JSON result verbatim.

All three operations return a ``workspaceEdit`` (``changes`` + ``fileOperations``) that the sidecar has already run
through its before/after javac validator (``diagnosticDeltaValidated: true``); the sidecar never writes files, so the
caller's transactional applier owns apply. A refusal carries ``accepted: false`` with a ``refusal`` object
(``code``/``message``) drawn from the §8.4/§9.4/§10.3 refusal lists.
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class ClassRefactorClient:
    """Forwards ``classRefactor.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def extract_class(
        self,
        relative_path: str,
        new_class_name: str,
        members: list[str],
        *,
        target_package: str | None = None,
        leave_delegate_methods: bool = True,
        update_usages: bool = False,
        confirm_public_api_change: bool = False,
        validate: bool = True,
    ) -> dict[str, Any]:
        """Plans an extract-class (§8): pull ``members`` out of the class in ``relative_path`` into ``new_class_name``.

        ``members`` are selectors of the form ``"field:<name>"`` or ``"method:<name>(<types>)"``. ``target_package``
        defaults to the source package. ``leave_delegate_methods`` keeps a forwarding stub in the source for each moved
        method (required for moving public methods). ``update_usages``, when true together with
        ``leave_delegate_methods=False``, rewrites external call sites of removed methods through a generated public
        delegate accessor instead of refusing. ``validate`` runs the sidecar's before/after javac validation.
        """
        params: dict[str, Any] = {
            "relativePath": relative_path,
            "newClassName": new_class_name,
            "members": members,
            "leaveDelegateMethods": leave_delegate_methods,
            "updateUsages": update_usages,
            "confirmPublicApiChange": confirm_public_api_change,
            "validate": validate,
        }
        if target_package is not None:
            params["targetPackage"] = target_package
        return self._client._request("classRefactor.extractClass", {"params": params})

    def extract_superclass(
        self,
        classes: list[str],
        superclass_name: str,
        members: list[str],
        *,
        target_package: str | None = None,
        make_abstract: bool = True,
        validate: bool = True,
    ) -> dict[str, Any]:
        """Plans an extract-superclass (§9): hoist ``members`` common to ``classes`` into ``superclass_name``.

        ``classes`` are project-relative paths, FQNs, or ``fqn:``/``symbol:`` keys for the sibling classes (at least
        one). ``members`` are selectors of the form ``"field:<name>"`` or ``"method:<name>(<types>)"`` that must exist
        on every selected class. Per §9.4, the operation is refused if any selected class already extends a non-Object
        superclass.

        When ``make_abstract`` is true, each hoisted METHOD becomes an ``abstract`` declaration in the generated
        superclass while every subclass keeps its concrete override (annotated ``@Override``); fields ignore the flag and
        are always pulled up wholesale. Forwarded to the sidecar as ``makeAbstract``. ``validate`` runs the sidecar's
        before/after javac validation.
        """
        params: dict[str, Any] = {
            "classes": classes,
            "superclassName": superclass_name,
            "members": members,
            "makeAbstract": make_abstract,
            "validate": validate,
        }
        if target_package is not None:
            params["targetPackage"] = target_package
        return self._client._request("classRefactor.extractSuperclass", {"params": params})

    def replace_inheritance_with_delegation(
        self,
        relative_path: str,
        *,
        members: list[str] | None = None,
        delegate_field_name: str | None = None,
        superclass_fqn: str | None = None,
        confirm_public_api_change: bool = False,
        validate: bool = True,
    ) -> dict[str, Any]:
        """Plans a replace-inheritance-with-delegation (§10) on the class in ``relative_path``.

        Drops the ``extends`` clause (a co-located ``implements`` clause is PRESERVED), introduces a ``private final``
        delegate field for the former superclass, and synthesizes a forwarding method for each selected inherited public
        instance method (``members`` as plain names or ``"method:<name>"`` selectors; empty selects all forwardable
        methods); forwarder signatures use simple type names with imports added. Per §10.3, generic/sealed superclasses,
        complex super constructors, and a dependency on a ``protected`` superclass member are refused. ``validate`` runs
        the sidecar's before/after javac validation.

        ``superclass_fqn`` is forwarded as ``superclassFqn``; ``ReplaceInheritanceWithDelegationPlanner`` verifies it
        matches the javac-resolved direct superclass and refuses with ``replace_inheritance_superclass_mismatch`` on a
        mismatch.

        ``confirm_public_api_change`` is forwarded as ``confirmPublicApiChange``; severing ``extends Base`` drops the
        supertype from the subclass's public API, so the planner refuses with ``replace_inheritance_public_api_change``
        unless this is true (the §10.3 default is to block the public-API change).
        """
        params: dict[str, Any] = {
            "relativePath": relative_path,
            "members": members or [],
            "confirmPublicApiChange": confirm_public_api_change,
            "validate": validate,
        }
        if delegate_field_name is not None:
            params["delegateFieldName"] = delegate_field_name
        if superclass_fqn is not None:
            params["superclassFqn"] = superclass_fqn
        return self._client._request("classRefactor.replaceInheritanceWithDelegation", {"params": params})
