"""Local patch: tools on request (load_tools), round status line, and tool
schemas counted in the trim budget."""

import asyncio
import json

import pytest

import src.agent_loop as al
import src.tool_groups as tg
from src.agent_tools import TOOL_HANDLERS
from src.tool_capabilities import ToolEffect, capabilities_for_tool
from src.tool_schemas import FUNCTION_TOOL_SCHEMAS


@pytest.fixture(autouse=True)
def _fresh_groups(monkeypatch):
    monkeypatch.setattr(tg, "_loaded", type(tg._loaded)())


def _run(coro):
    return asyncio.run(coro)


# ── catalog and handler ──────────────────────────────────────────────


def test_parse_groups_accepts_json_list_and_plain_text():
    assert tg.parse_groups('{"groups": ["browser", "email"]}') == (["browser", "email"], [])
    assert tg.parse_groups('{"group": "Email"}') == (["email"], [])
    assert tg.parse_groups('["documents"]') == (["documents"], [])
    assert tg.parse_groups("browser, email") == (["browser", "email"], [])
    assert tg.parse_groups('{"groups": ["browser", "nope"]}') == (["browser"], ["nope"])


def test_load_tools_handler_returns_loaded_groups():
    desc, result = _run(TOOL_HANDLERS["load_tools"]('{"groups": ["browser"]}', None))
    assert desc == "load_tools: browser"
    assert result["loaded_groups"] == ["browser"]
    assert result["exit_code"] == 0


def test_load_tools_handler_rejects_unknown_group_and_lists_valid_ones():
    _, result = _run(TOOL_HANDLERS["load_tools"]('{"groups": ["teleport"]}', None))
    assert result["exit_code"] == 1
    assert "teleport" in result["error"]
    assert "browser" in result["error"]
    assert "loaded_groups" not in result


def test_load_tools_schema_lists_every_group():
    schema = next(s for s in FUNCTION_TOOL_SCHEMAS if s["function"]["name"] == "load_tools")
    enum = schema["function"]["parameters"]["properties"]["groups"]["items"]["enum"]
    assert enum == list(tg.TOOL_GROUPS)
    for name in tg.TOOL_GROUPS:
        assert name in schema["function"]["description"]


def test_load_tools_has_no_side_effect_capability():
    caps = capabilities_for_tool("load_tools")
    assert caps is not None
    assert ToolEffect.USER_INTERACTION in caps.effects


def test_groups_are_remembered_per_chat():
    tg.remember_groups("chat-a", ["browser", "bogus"])
    assert tg.loaded_groups("chat-a") == {"browser"}
    assert tg.loaded_groups("chat-b") == set()
    assert tg.loaded_groups(None) == set()
    assert "builtin_browser" in tg.tools_for_groups(tg.loaded_groups("chat-a"))


# ── status line ──────────────────────────────────────────────────────


def test_status_line_reports_step_and_context():
    line = al._round_status_line(3, 80, 10_000, 100_000)
    assert line == "\n\n[odysseus status: step 3 of 80 · context 10% full]"


def test_status_line_flags_high_context_then_cut():
    assert "· context high]" in al._round_status_line(10, 80, 72_000, 100_000)
    cut = al._round_status_line(10, 80, 130_000, 100_000)
    assert "context 100% full · older steps being cut from view]" in cut
    assert "context high" not in cut


def test_status_line_flags_near_round_cap():
    assert "· 3 steps left]" in al._round_status_line(77, 80, 1_000, 100_000)


def test_status_line_carries_no_instructions():
    # Orders inside tool output read as prompt injection to the model.
    line = al._round_status_line(79, 80, 99_000, 100_000).lower()
    for word in ("update", "notes.md", "must", "should", "now"):
        assert word not in line


def test_schema_token_estimate():
    assert al._schema_token_estimate([]) == 0
    schemas = [{"type": "function", "function": {"name": "x" * 100}}]
    assert al._schema_token_estimate(schemas) == int(len(json.dumps(schemas)) * 0.3)


# ── agent loop ───────────────────────────────────────────────────────


def _collect(gen):
    async def _go():
        return [c async for c in gen]
    return _run(_go())


def _drive(monkeypatch, replies, session_id="chat-1", max_rounds=4):
    """Run the real loop with a fake model; return the tool names and the
    messages it was sent each round."""
    monkeypatch.setattr(al, "get_setting", lambda key, default=None: default, raising=False)
    monkeypatch.setattr(al, "get_mcp_manager", lambda: None, raising=False)
    monkeypatch.setattr(al, "blocked_tools_for_owner", lambda owner: set(), raising=False)

    async def _exec(block, *a, **k):
        if block.tool_type == "load_tools":
            return await TOOL_HANDLERS["load_tools"](block.content, None)
        return (block.tool_type, {"output": "ok", "exit_code": 0})
    monkeypatch.setattr(al, "execute_tool_block", _exec, raising=False)

    sent = []
    queue = list(replies)

    async def _stream(candidates, messages, **kwargs):
        factory = kwargs.get("candidate_request_factory")
        request = await factory(0, *candidates[0]) if factory else {"messages": messages, "kwargs": kwargs}
        tools = request["kwargs"].get("tools") or []
        sent.append((
            {t["function"]["name"] for t in tools if t.get("function")},
            request["messages"],
        ))
        reply = queue.pop(0) if queue else "All done."
        if isinstance(reply, tuple):
            name, args = reply
            call = {"name": name, "arguments": json.dumps(args)}
            yield f'data: {json.dumps({"type": "tool_calls", "calls": [call]})}\n\n'
        else:
            yield f'data: {json.dumps({"delta": reply})}\n\n'
        yield "data: [DONE]\n\n"
    monkeypatch.setattr(al, "stream_llm_with_fallback", _stream, raising=False)

    _collect(al.stream_agent_loop(
        "https://api.example.com/v1", "gpt-4o",
        [{"role": "user", "content": "Run ls in my workspace folder, check the build script and tell me which files are missing"}],
        max_rounds=max_rounds,
        session_id=session_id,
    ))
    return sent


def test_loop_starts_small_and_loads_a_group_for_the_next_round(monkeypatch):
    monkeypatch.setenv("ODYSSEUS_TOOLS_ON_REQUEST", "1")
    monkeypatch.delenv("ODYSSEUS_ROUND_STATUS", raising=False)
    sent = _drive(monkeypatch, [("load_tools", {"groups": ["documents"]}), "All done."])

    assert len(sent) >= 2, sent
    first, second = sent[0][0], sent[1][0]
    assert "load_tools" in first
    assert "bash" in first
    assert "create_document" not in first
    assert "create_document" in second
    assert "stop_served_model" not in second
    assert tg.loaded_groups("chat-1") == {"documents"}


def test_loaded_group_carries_into_the_next_turn(monkeypatch):
    monkeypatch.setenv("ODYSSEUS_TOOLS_ON_REQUEST", "1")
    tg.remember_groups("chat-2", ["documents"])
    sent = _drive(monkeypatch, ["All done."], session_id="chat-2")
    assert "create_document" in sent[0][0]


def test_flag_off_never_offers_load_tools(monkeypatch):
    monkeypatch.delenv("ODYSSEUS_TOOLS_ON_REQUEST", raising=False)
    sent = _drive(monkeypatch, ["All done."])
    assert "load_tools" not in sent[0][0]


def test_status_line_is_stored_on_the_tool_result(monkeypatch):
    monkeypatch.setenv("ODYSSEUS_ROUND_STATUS", "1")
    sent = _drive(monkeypatch, [("update_plan", {"plan": "- [ ] a"}), "All done."])
    second_round_messages = sent[1][1]
    text = json.dumps(second_round_messages)
    assert "[odysseus status: step 1 of 4" in text


def test_status_line_off_by_default(monkeypatch):
    monkeypatch.delenv("ODYSSEUS_ROUND_STATUS", raising=False)
    sent = _drive(monkeypatch, [("update_plan", {"plan": "- [ ] a"}), "All done."])
    assert "[odysseus status:" not in json.dumps(sent[1][1])


def test_trim_with_tool_list_never_skips(monkeypatch, caplog):
    # The first trim runs before the tool-list builder exists; asking it for
    # schemas there raised, and the trim was skipped without a word.
    monkeypatch.setenv("ODYSSEUS_TOOLS_ON_REQUEST", "1")
    monkeypatch.setenv("ODYSSEUS_TRIM_COUNTS_TOOLS", "1")
    with caplog.at_level("WARNING", logger="src.agent_loop"):
        _drive(monkeypatch, [("update_plan", {"plan": "- [ ] a"}), "All done."])
    assert "Soft context trim skipped" not in caplog.text


def test_trim_budget_drops_by_the_tool_schemas(monkeypatch):
    monkeypatch.setenv("ODYSSEUS_TRIM_COUNTS_TOOLS", "1")
    budgets = []
    import src.context_compactor as cc

    def _spy(messages, budget, reserve_tokens=0):
        budgets.append(budget)
        return list(messages)
    monkeypatch.setattr(cc, "trim_for_context", _spy)
    import src.model_context as mc
    monkeypatch.setattr(mc, "budget_context_for_model", lambda *a, **k: 40_000)
    _drive(monkeypatch, ["All done."])
    # 85% of 40K is 34K; every budget that counted the tool list is lower,
    # but never below a quarter of it.
    assert budgets and min(budgets) < 34_000
    assert min(budgets) >= 34_000 // 4


def test_flag_off_drops_load_tools_even_if_retrieval_picks_it(monkeypatch):
    monkeypatch.delenv("ODYSSEUS_TOOLS_ON_REQUEST", raising=False)
    monkeypatch.setattr(al, "get_setting", lambda key, default=None: default, raising=False)
    monkeypatch.setattr(al, "get_mcp_manager", lambda: None, raising=False)
    monkeypatch.setattr(al, "blocked_tools_for_owner", lambda owner: set(), raising=False)
    sent = []

    async def _stream(candidates, messages, **kwargs):
        request = await kwargs["candidate_request_factory"](0, *candidates[0])
        sent.append({t["function"]["name"] for t in request["kwargs"].get("tools") or []})
        yield f'data: {json.dumps({"delta": "All done."})}\n\n'
        yield "data: [DONE]\n\n"
    monkeypatch.setattr(al, "stream_llm_with_fallback", _stream, raising=False)
    _collect(al.stream_agent_loop(
        "https://api.example.com/v1", "gpt-4o",
        [{"role": "user", "content": "Run ls in my workspace folder and report"}],
        max_rounds=2,
        relevant_tools={"bash", "load_tools"},
    ))
    assert "bash" in sent[0]
    assert "load_tools" not in sent[0]


class _FakeMcp:
    def get_all_tools(self):
        return [
            {"server_id": "builtin_browser", "qualified_name": "mcp__builtin_browser__browser_navigate"},
            {"server_id": "builtin_browser", "qualified_name": "mcp__builtin_browser__browser_click"},
            {"server_id": "other", "qualified_name": "mcp__other__thing"},
        ]


def test_loading_browser_adds_every_browser_tool():
    names = al._expand_browser_mcp_tools({"bash"} | tg.tools_for_groups(["browser"]), _FakeMcp())
    assert "mcp__builtin_browser__browser_navigate" in names
    assert "mcp__builtin_browser__browser_click" in names
    assert "mcp__other__thing" not in names


def test_core_tools_alone_pull_in_no_browser_tools():
    from src.tool_index import ALWAYS_AVAILABLE
    names = al._expand_browser_mcp_tools(set(ALWAYS_AVAILABLE) | {"load_tools"}, _FakeMcp())
    assert not any(n.startswith("mcp__builtin_browser__") for n in names)
