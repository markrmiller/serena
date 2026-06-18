"""Live-sidecar coverage for the G-CACHE revision-keyed ``ReachabilityGraph`` cache (refactor-feature-plan-V3.md §3
F-GRAPH).

The ``ReachabilityGraph`` was previously rebuilt on every request at three sites (``impact.facts``,
``deletion.findDeadCode``, ``deletion.propagateSafeDelete``). ``ReachabilityGraphCache`` now memoizes it on a
WHOLE-PROJECT revision key. These tests boot the real Java sidecar jar and observe the graph build counter (exposed by
the diagnostic ``reachabilityGraph.buildCount`` op, which advances on a cache MISS and stays flat on a HIT) to prove:

* same project revision + same ``includeTests`` → the graph is REUSED (no rebuild) — a cache HIT;
* changed source content → a new key → a REBUILD — a cache MISS;
* the keying trap: changing an UNTOUCHED source file (not in the ``impact.facts`` touched set) STILL invalidates the
  cache, because the key is whole-project, not touched-file-scoped. Keying on a touched-file token would silently
  serve a stale graph here and corrupt impact facts — this test is the guard against that regression.

The build counter is the explicit hit/miss observation point: ``ReachabilityGraph.build`` increments a static counter,
and ``ReachabilityGraphCache`` only invokes ``build`` on a miss, so an unchanged ``buildCount`` across two requests
proves the cached graph instance was returned without a rebuild.
"""

from __future__ import annotations

from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.impact_facts_client import ImpactFactsClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _seed_project(root: Path) -> None:
    # App references Helper, so the reachability graph has real nodes and an edge to walk.
    _write(root, "src/main/java/com/acme/Helper.java",
           "package com.acme;\npublic class Helper { public int value() { return 1; } }\n")
    _write(root, "src/main/java/com/acme/App.java",
           "package com.acme;\npublic class App { int go() { return new Helper().value(); } }\n")


def test_graph_cache_hit_until_touched_source_changes(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar, java_command=sidecar_java_cmd)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        facts = ImpactFactsClient(client)

        def builds() -> int:
            return int(client._request("reachabilityGraph.buildCount", {})["builds"])

        base = builds()

        # First impact.facts → cache MISS → exactly one graph build.
        r1 = facts.facts(["src/main/java/com/acme/Helper.java"])
        assert r1["accepted"] is True
        after_first = builds()
        assert after_first == base + 1, "first impact.facts must build the graph once"

        # Second identical call (no source change) → cache HIT → no rebuild (same graph instance reused).
        r2 = facts.facts(["src/main/java/com/acme/Helper.java"])
        assert r2["accepted"] is True
        assert builds() == after_first, "repeat request over an unchanged project must reuse the cached graph"

        # Change the touched source's content → new whole-project key → cache MISS → rebuild.
        _write(tmp_path, "src/main/java/com/acme/Helper.java",
               "package com.acme;\npublic class Helper { public int value() { return 2; } }\n")
        r3 = facts.facts(["src/main/java/com/acme/Helper.java"])
        assert r3["accepted"] is True
        assert builds() == after_first + 1, "changed source content must invalidate the cache and rebuild"
    finally:
        client.shutdown()


def test_graph_cache_invalidates_on_untouched_source_change(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    """The keying trap: the cache key is WHOLE-PROJECT, so editing a file OUTSIDE the touched set still rebuilds.

    A touched-file-scoped key (``ProjectRevision.stableToken()``) would NOT change here and would silently serve a
    stale graph → wrong impact facts. An incrementing build count proves the key captures the whole project.
    """
    _seed_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar, java_command=sidecar_java_cmd)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        facts = ImpactFactsClient(client)

        def builds() -> int:
            return int(client._request("reachabilityGraph.buildCount", {})["builds"])

        base = builds()

        # Touched set is Helper.java only.
        r1 = facts.facts(["src/main/java/com/acme/Helper.java"])
        assert r1["accepted"] is True
        after_first = builds()
        assert after_first == base + 1

        # Confirm the baseline is a cache hit before the untouched-file edit.
        facts.facts(["src/main/java/com/acme/Helper.java"])
        assert builds() == after_first

        # Edit App.java — NOT in the touched set — yet it changes the graph. Whole-project key must miss + rebuild.
        _write(tmp_path, "src/main/java/com/acme/App.java",
               "package com.acme;\npublic class App { int go() { return new Helper().value() + 7; } }\n")
        r2 = facts.facts(["src/main/java/com/acme/Helper.java"])
        assert r2["accepted"] is True
        assert builds() == after_first + 1, (
            "an edit to an UNTOUCHED source file must invalidate the whole-project cache key (keying-trap guard)"
        )
    finally:
        client.shutdown()
