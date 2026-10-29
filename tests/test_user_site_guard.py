"""Local patch: the app ignores user-site packages; the agent's runs keep them."""

import os
import sys

from src import builtin_mcp, user_site_guard


def _fake_user_site(monkeypatch, tmp_path):
    base = tmp_path / "home" / ".local"
    user_site = base / "lib" / "python3.14" / "site-packages"
    user_site.mkdir(parents=True)
    monkeypatch.setattr(user_site_guard.site, "USER_BASE", str(base))
    monkeypatch.setattr(user_site_guard.site, "USER_SITE", str(user_site))
    return str(user_site)


def test_drops_user_site_from_sys_path(monkeypatch, tmp_path):
    monkeypatch.delenv("ODYSSEUS_ALLOW_USER_SITE", raising=False)
    user_site = _fake_user_site(monkeypatch, tmp_path)
    image_site = str(tmp_path / "usr" / "site-packages")
    monkeypatch.setattr(sys, "path", [user_site, image_site])
    removed = user_site_guard.drop_user_site()
    assert removed == [user_site]
    assert sys.path == [image_site]


def test_opt_out_keeps_user_site(monkeypatch, tmp_path):
    monkeypatch.setenv("ODYSSEUS_ALLOW_USER_SITE", "1")
    user_site = _fake_user_site(monkeypatch, tmp_path)
    monkeypatch.setattr(sys, "path", [user_site])
    assert user_site_guard.drop_user_site() == []
    assert sys.path == [user_site]


def test_builtin_python_servers_start_without_user_site(monkeypatch):
    monkeypatch.delenv("ODYSSEUS_ALLOW_USER_SITE", raising=False)
    import inspect
    from src import mcp_manager
    # Both launch sites add the guard env on top of builtin_python_env().
    for module in (builtin_mcp, mcp_manager):
        assert "**builtin_python_env(base_dir), **no_user_site_env()" in inspect.getsource(module)
    assert user_site_guard.no_user_site_env() == {"PYTHONNOUSERSITE": "1"}


def test_no_user_site_env_opt_out(monkeypatch):
    monkeypatch.setenv("ODYSSEUS_ALLOW_USER_SITE", "1")
    assert user_site_guard.no_user_site_env() == {}
