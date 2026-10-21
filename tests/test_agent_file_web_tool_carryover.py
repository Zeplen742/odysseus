"""Vague follow-ups keep the shell/file and web tools the previous turn ran.

Regression for #4365: after a turn that ran write_file/bash, a follow-up such
as "ok now finish it" took the direct low-signal path (no tools) or was
clamped to ask_user, so the agent could not continue the task.
"""
import asyncio
import json
from types import SimpleNamespace

import pytest

import src.agent_loop as al


def _assistant_with_tools(*tools):
    return SimpleNamespace(
        role="assistant",
        content="The script failed because the mesh is not oriented. I can fix it.",
        metadata={"tool_events": [{"tool": tool, "exit_code": 0} for tool in tools]},
    )


def _carried(history, latest):
    session = SimpleNamespace(history=[*history, SimpleNamespace(role="user", content=latest, metadata={})])
    return al._domain_tools_from_previous_assistant_turn(
        [{"role": "user", "content": latest}], latest, history_session=session,
    )


FIRST_ASK = SimpleNamespace(
    role="user",
    content="Write a python script that engraves a label on the bottom of model.stl, then run it.",
    metadata={},
)


@pytest.mark.parametrize("latest", ["ok now finish it", "try again", "fix it", "keep going"])
def test_file_tool_turn_carries_files_domain(latest):
    history = [FIRST_ASK, _assistant_with_tools("write_file", "bash")]
    assert _carried(history, latest) == {"files"}


def test_web_tool_turn_carries_web_domain():
    history = [
        SimpleNamespace(role="user", content="find the release notes for 1.0.3", metadata={}),
        _assistant_with_tools("web_search", "web_fetch"),
    ]
    assert _carried(history, "and the one before that") == {"web"}


def test_casual_turn_after_file_tools_does_not_carry():
    history = [FIRST_ASK, _assistant_with_tools("write_file", "bash")]
    assert _carried(history, "thanks") == set()


def test_carry_expires_after_a_turn_without_tools():
    history = [
        FIRST_ASK,
        _assistant_with_tools("write_file", "bash"),
        SimpleNamespace(role="user", content="what does it do?", metadata={}),
        SimpleNamespace(role="assistant", content="It cuts the label into the bottom face.", metadata={}),
    ]
    assert _carried(history, "ok now finish it") == set()


def _sent_tool_names(monkeypatch, messages, *, workspace):
    monkeypatch.setattr(al, "get_setting", lambda key, default=None: default, raising=False)
    monkeypatch.setattr(al, "get_mcp_manager", lambda: None, raising=False)
    monkeypatch.setattr(al, "estimate_tokens", lambda *a, **k: 10, raising=False)
    monkeypatch.setattr(al, "blocked_tools_for_owner", lambda owner: set(), raising=False)
    captured = []

    async def _fake_stream(_candidates, msgs, **kwargs):
        captured.append(kwargs.get("tools"))
        yield "data: " + json.dumps({"delta": "ok"}) + "\n\n"
        yield "data: [DONE]\n\n"

    monkeypatch.setattr(al, "stream_llm_with_fallback", _fake_stream, raising=False)

    async def _run():
        gen = al.stream_agent_loop(
            "https://api.openai.com/v1", "gpt-test", messages,
            max_rounds=1, relevant_tools=None, owner="admin", workspace=workspace,
        )
        return [c async for c in gen]

    asyncio.run(_run())
    assert captured, "turn never reached the model"
    return {t["function"]["name"] for t in (captured[0] or []) if isinstance(t, dict) and "function" in t}


@pytest.mark.parametrize("workspace", ["/tmp", None])
@pytest.mark.parametrize("latest", ["ok now finish it", "try again", "keep going"])
def test_vague_followup_after_file_tools_keeps_them(monkeypatch, workspace, latest):
    messages = [
        {"role": "user", "content": FIRST_ASK.content},
        {
            "role": "assistant",
            "content": "The script failed because the mesh is not oriented. I can fix it.",
            "metadata": {"tool_events": [
                {"tool": "write_file", "exit_code": 0},
                {"tool": "bash", "exit_code": 1},
            ]},
        },
        {"role": "user", "content": latest},
    ]
    names = _sent_tool_names(monkeypatch, messages, workspace=workspace)
    for tool in ("write_file", "edit_file", "bash", "python", "read_file"):
        assert tool in names
