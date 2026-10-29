"""Local patch: the app ignores packages installed into the user site.

The agent can ``pip install --user`` into HOME's user site, which in Docker
lives on the data volume and survives image updates. Python puts that folder
ahead of the image's site-packages, so an install for one job (2026-10-26:
vLLM, which brought mcp 2.x, an older fastapi and numpy) silently replaced the
app's pinned libraries. Under mcp 2.x every MCP tool schema read as empty and
the built-in Python MCP servers stopped starting.

The app process and the built-in MCP servers now run on the image's packages
only. The agent's own shell and Python runs still see the user site, so its
installs keep working for its jobs. ODYSSEUS_ALLOW_USER_SITE=1 restores the
old behaviour.
"""

import logging
import os
import site
import sys
from typing import List

logger = logging.getLogger(__name__)


def user_site_allowed() -> bool:
    return os.environ.get("ODYSSEUS_ALLOW_USER_SITE", "").lower() in ("1", "true", "yes")


def _user_site_prefixes() -> List[str]:
    prefixes = []
    for value in (getattr(site, "USER_SITE", None), getattr(site, "USER_BASE", None)):
        if value:
            prefixes.append(os.path.realpath(value))
    return prefixes


def _under(path: str, prefixes: List[str]) -> bool:
    try:
        real = os.path.realpath(path)
    except (TypeError, ValueError):
        return False
    return any(real == p or real.startswith(p + os.sep) for p in prefixes)


def drop_user_site() -> List[str]:
    """Remove user-site entries from sys.path; return what was removed.

    Also warns about modules already imported from there (anything loaded
    before app.py, e.g. by the ASGI server)."""
    if user_site_allowed():
        return []
    prefixes = _user_site_prefixes()
    if not prefixes:
        return []
    removed = [p for p in sys.path if p and _under(p, prefixes)]
    if removed:
        sys.path[:] = [p for p in sys.path if not (p and _under(p, prefixes))]
    early = sorted(
        name for name, mod in list(sys.modules.items())
        if "." not in name and _under(getattr(mod, "__file__", None) or "", prefixes)
    )
    if early:
        logger.warning("Modules already imported from the user site before the guard: %s", early)
    return removed


def no_user_site_env() -> dict:
    """Env for app-owned Python subprocesses (built-in MCP servers)."""
    return {} if user_site_allowed() else {"PYTHONNOUSERSITE": "1"}
