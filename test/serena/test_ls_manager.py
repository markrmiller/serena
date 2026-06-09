"""Unit tests for LanguageServerManager, in particular the `lazy_start` behavior.

These tests use lightweight fakes instead of real language servers, so they do not require any
language toolchain and are not gated behind a language marker.
"""

from solidlsp.ls_config import Language

from serena.ls_manager import LanguageServerManager


class FakeLanguageServer:
    def __init__(self, language: Language):
        self.language = language
        self.start_count = 0
        self.stop_count = 0
        self.save_cache_count = 0
        self._running = False

    def start(self) -> None:
        self.start_count += 1
        self._running = True

    def is_running(self) -> bool:
        return self._running

    def stop(self, shutdown_timeout: float = 2.0) -> None:
        self.stop_count += 1
        self._running = False

    def save_cache(self) -> None:
        self.save_cache_count += 1

    def is_ignored_path(self, relative_path: str, ignore_unsupported_files: bool = True) -> bool:
        return False


class FakeFactory:
    def __init__(self, ls_specific_settings: dict | None = None):
        self.ls_specific_settings = ls_specific_settings
        self.created: dict[Language, FakeLanguageServer] = {}

    def create_language_server(self, language: Language) -> FakeLanguageServer:
        ls = FakeLanguageServer(language)
        self.created[language] = ls
        return ls


def _make_manager(ls_specific_settings: dict | None) -> tuple[LanguageServerManager, FakeFactory]:
    factory = FakeFactory(ls_specific_settings)
    manager = LanguageServerManager.from_languages([Language.JAVA], factory)
    return manager, factory


def test_eager_start_starts_immediately() -> None:
    manager, factory = _make_manager({})
    ls = factory.created[Language.JAVA]
    assert ls.start_count == 1
    assert ls.is_running()


def test_lazy_start_defers_until_first_use() -> None:
    manager, factory = _make_manager({"java": {"lazy_start": True}})
    ls = factory.created[Language.JAVA]
    # created but not started at activation
    assert ls.start_count == 0
    assert not ls.is_running()
    # the server is still managed (visible to e.g. get_active_languages)
    assert manager.get_active_languages() == [Language.JAVA]

    # first symbolic access starts it
    returned = manager.get_language_server("Foo.java")
    assert returned is ls
    assert ls.start_count == 1
    assert ls.is_running()

    # subsequent access does not start it again
    manager.get_language_server("Bar.java")
    assert ls.start_count == 1


def test_iter_language_servers_starts_lazy() -> None:
    manager, factory = _make_manager({"java": {"lazy_start": True}})
    ls = factory.created[Language.JAVA]
    assert ls.start_count == 0
    list(manager.iter_language_servers())
    assert ls.start_count == 1


def test_stop_all_skips_unstarted_lazy() -> None:
    manager, factory = _make_manager({"java": {"lazy_start": True}})
    ls = factory.created[Language.JAVA]
    manager.stop_all()
    # never started, so it is neither started-to-stop nor stopped
    assert ls.start_count == 0
    assert ls.stop_count == 0


def test_save_all_caches_skips_unstarted_lazy() -> None:
    manager, factory = _make_manager({"java": {"lazy_start": True}})
    ls = factory.created[Language.JAVA]
    manager.save_all_caches()
    assert ls.start_count == 0
    assert ls.save_cache_count == 0


def test_started_lazy_server_is_stopped() -> None:
    manager, factory = _make_manager({"java": {"lazy_start": True}})
    ls = factory.created[Language.JAVA]
    manager.get_language_server("Foo.java")  # triggers lazy start
    manager.stop_all()
    assert ls.stop_count == 1
