"""Local patch: trimming keeps the prompt prefix stable across agent rounds.

Each round re-trims the whole history. If the trimmed prompt only grows at the
end from one round to the next, a local backend (llama.cpp) reuses its cached
prefix instead of re-reading the prompt. Rules stay in every round.
"""
from src.context_compactor import trim_for_context
from src.model_context import estimate_tokens

RULES = {"role": "system", "content": "RULES: use the kit. " * 40, "_protected": True}
BASE = [
    {"role": "system", "content": "You are an agent. " * 60},
    RULES,
    {"role": "user", "content": "Engrave TRUELINE on the back of the tray."},
]


def _round_messages(n_calls):
    msgs = list(BASE)
    for i in range(n_calls):
        msgs.append({"role": "assistant", "content": None, "tool_calls": [
            {"id": f"c{i}", "type": "function", "function": {"name": "bash", "arguments": '{"command": "python3 x.py"}'}}]})
        msgs.append({"role": "tool", "tool_call_id": f"c{i}", "content": f"result {i}: " + "x" * 2000})
    return msgs


def _is_prefix(a, b):
    return len(a) <= len(b) and all(x == y for x, y in zip(a, b))


def _rounds(n=30, ctx=8000):
    return [trim_for_context(_round_messages(k), ctx, reserve_tokens=512) for k in range(1, n + 1)]


def test_most_rounds_only_append():
    out = _rounds()
    trimmed = [i for i in range(1, len(out)) if estimate_tokens(_round_messages(i + 1)) > 8000 - 512]
    appends = sum(1 for i in trimmed if _is_prefix(out[i - 1], out[i]))
    assert len(trimmed) >= 15
    assert appends >= 0.6 * len(trimmed), f"only {appends}/{len(trimmed)} trimmed rounds kept the prefix"


def test_rules_request_and_budget_kept_every_round():
    for k, msgs in enumerate(_rounds(), start=1):
        assert any(m.get("content") == RULES["content"] for m in msgs), f"rules dropped in round {k}"
        assert any(m.get("role") == "user" and "TRUELINE" in str(m.get("content")) for m in msgs)
        assert estimate_tokens(msgs) <= 8000 - 512 + 100, f"round {k} over budget"


def test_newest_tool_results_stay_whole():
    msgs = _rounds()[-1]
    tool = [m for m in msgs if m.get("role") == "tool"]
    assert tool[-1]["content"].endswith("x" * 2000)
    assert tool[-2]["content"].endswith("x" * 2000)


def test_old_images_become_a_note():
    from src.context_compactor import _stub_message
    msg = {"role": "user", "content": [{"type": "text", "text": "render"},
                                       {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}]}
    stub = _stub_message(msg)
    assert not any(p.get("type") == "image_url" for p in stub["content"])
    assert "older image" in stub["content"][-1]["text"]
    assert _stub_message(msg) == stub  # same stub every round
