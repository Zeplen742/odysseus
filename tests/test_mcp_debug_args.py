"""Local patch: ODYSSEUS_DEBUG_MCP_ARGS logs MCP schemas and call arguments."""

import logging

import src.agent_loop  # noqa: F401  (app import order)
from src import builtin_mcp, llm_core


def _tools():
    return [
        {"type": "function", "function": {"name": "mcp__builtin_browser__browser_navigate",
                                          "parameters": {"type": "object", "properties": {"url": {"type": "string"}}}}},
        {"type": "function", "function": {"name": "mcp__builtin_browser__browser_close",
                                          "parameters": {"type": "object", "properties": {}}}},
        {"type": "function", "function": {"name": "bash", "parameters": {"type": "object", "properties": {}}}},
    ]


def test_schema_log_is_off_by_default(monkeypatch, caplog):
    monkeypatch.delenv("ODYSSEUS_DEBUG_MCP_ARGS", raising=False)
    with caplog.at_level(logging.INFO, logger="src.llm_core"):
        llm_core._debug_log_mcp_schemas(_tools())
    assert "[mcp-debug]" not in caplog.text


def test_schema_log_names_navigate_and_empty_mcp_schemas_only(monkeypatch, caplog):
    monkeypatch.setenv("ODYSSEUS_DEBUG_MCP_ARGS", "1")
    with caplog.at_level(logging.INFO, logger="src.llm_core"):
        llm_core._debug_log_mcp_schemas(_tools())
    assert "browser_navigate" in caplog.text
    assert "browser_close" in caplog.text
    assert "schema sent bash" not in caplog.text


def test_browser_mcp_is_pinned():
    args = builtin_mcp._BUILTIN_NPX_SERVERS["builtin_browser"]["args"]
    assert "@playwright/mcp@0.0.83" in args
