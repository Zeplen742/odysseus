"""Local patch: tools on request (ODYSSEUS_TOOLS_ON_REQUEST=1).

An agent turn starts with the always-available tools (files, shell, python,
web, memory, ask_user, plan). Everything else sits in named groups that the
model loads with the ``load_tools`` tool when a job needs them, instead of a
retrieval guess picking tools from the wording of the message. A loaded group
stays loaded for the rest of the chat.

No imports from the agent loop or tool packages, so the schema list, the tool
handler and the loop can all share this one catalog.
"""

import os
import threading
from collections import OrderedDict
from typing import Dict, FrozenSet, Iterable, List, Set, Tuple

# group -> (one-line description for the model, tool names)
# "builtin_browser" is the marker the agent loop expands to every connected
# Playwright MCP tool.
TOOL_GROUPS: Dict[str, Tuple[str, FrozenSet[str]]] = {
    "browser": (
        "drive a real web browser: open pages, click, type, screenshot (for JavaScript pages web_fetch can't read)",
        frozenset({"builtin_browser"}),
    ),
    "documents": (
        "create and edit documents in the editor panel",
        frozenset({"create_document", "edit_document", "update_document", "suggest_document", "manage_documents"}),
    ),
    "email": (
        "read, send, reply to, archive and delete email",
        frozenset({
            "list_email_accounts", "list_emails", "read_email", "scan_email_unsubscribes",
            "unsubscribe_email", "send_email", "reply_to_email", "bulk_email", "archive_email",
            "delete_email", "mark_email_read", "resolve_contact", "manage_contact",
        }),
    ),
    "contacts": (
        "look up and manage contacts",
        frozenset({"resolve_contact", "manage_contact"}),
    ),
    "notes_calendar_tasks": (
        "notes, calendar events and scheduled tasks",
        frozenset({"manage_notes", "manage_calendar", "manage_tasks"}),
    ),
    "models": (
        "local LLM serving (download, serve, stop models) and asking other models",
        frozenset({
            "download_model", "serve_model", "serve_preset", "list_serve_presets",
            "list_served_models", "stop_served_model", "tail_serve_output", "list_downloads",
            "cancel_download", "search_hf_models", "list_cached_models",
            "list_cookbook_servers", "adopt_served_model",
            "list_models", "chat_with_model", "ask_teacher",
        }),
    ),
    "sessions": (
        "other chats: create, list, message, search past chats",
        frozenset({"create_session", "list_sessions", "manage_session", "send_to_session", "search_chats"}),
    ),
    "settings": (
        "app settings, endpoints, MCP servers, webhooks, API tokens",
        frozenset({"manage_settings", "manage_endpoints", "manage_mcp", "manage_webhooks", "manage_tokens", "app_api"}),
    ),
    "ui": (
        "switch app tools on or off and open panels",
        frozenset({"ui_control"}),
    ),
    "integrations": (
        "call configured HTTP integrations",
        frozenset({"api_call"}),
    ),
    "research": (
        "start a multi-source deep-research report",
        frozenset({"trigger_research"}),
    ),
    "skills": (
        "view and manage saved skills",
        frozenset({"manage_skills"}),
    ),
    "pipeline": (
        "run a multi-step pipeline of AI calls",
        frozenset({"pipeline"}),
    ),
}

LOAD_TOOLS_NAME = "load_tools"

_MAX_SESSIONS = 512
_loaded: "OrderedDict[str, Set[str]]" = OrderedDict()
_lock = threading.Lock()


def tools_on_request_enabled() -> bool:
    return os.environ.get("ODYSSEUS_TOOLS_ON_REQUEST", "").lower() in ("1", "true", "yes")


def tools_for_groups(groups: Iterable[str]) -> Set[str]:
    names: Set[str] = set()
    for group in groups or ():
        entry = TOOL_GROUPS.get(group)
        if entry:
            names |= entry[1]
    return names


def loaded_groups(session_id) -> Set[str]:
    if not session_id:
        return set()
    with _lock:
        return set(_loaded.get(str(session_id), set()))


def remember_groups(session_id, groups: Iterable[str]) -> None:
    if not session_id:
        return
    key = str(session_id)
    with _lock:
        current = _loaded.pop(key, set())
        current |= {g for g in groups if g in TOOL_GROUPS}
        _loaded[key] = current
        while len(_loaded) > _MAX_SESSIONS:
            _loaded.popitem(last=False)


def parse_groups(content: str) -> Tuple[List[str], List[str]]:
    """Return (known groups, unknown names) from a load_tools call body."""
    import json

    raw = (content or "").strip()
    items: List[str] = []
    try:
        parsed = json.loads(raw) if raw else {}
    except (ValueError, TypeError):
        parsed = None
    if isinstance(parsed, dict):
        value = parsed.get("groups", parsed.get("group", []))
        items = [value] if isinstance(value, str) else list(value or [])
    elif isinstance(parsed, list):
        items = parsed
    else:
        items = raw.replace(",", " ").split()
    known: List[str] = []
    unknown: List[str] = []
    for item in items:
        name = str(item).strip().lower()
        if not name:
            continue
        target = known if name in TOOL_GROUPS else unknown
        if name not in target:
            target.append(name)
    return known, unknown


def load_tools_description() -> str:
    groups = "; ".join(f"{name}: {desc}" for name, (desc, _) in TOOL_GROUPS.items())
    return (
        "Load more tools for this job. You already have files, shell, python, web search/fetch, "
        "memory, ask_user and plan tools; call this only when the job needs something else. "
        "Loaded tools can be called directly in your next tool call and stay for the rest of the chat. "
        f"Groups: {groups}."
    )


def load_tools_schema() -> dict:
    return {
        "type": "function",
        "function": {
            "name": LOAD_TOOLS_NAME,
            "description": load_tools_description(),
            "parameters": {
                "type": "object",
                "properties": {
                    "groups": {
                        "type": "array",
                        "items": {"type": "string", "enum": list(TOOL_GROUPS)},
                        "description": "Group names to load.",
                    }
                },
                "required": ["groups"],
            },
        },
    }
