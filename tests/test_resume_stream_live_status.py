"""A re-attached run (resumeStream) keeps a live status until it ends.

The spinner used to be destroyed at the first token and steps/tools/thinking
were never shown, so a long agent job looked frozen on a phone for minutes.
chat.js needs browser globals, so this guards the fix at the source level.
"""
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "static/js/chat.js"


def _resume_body() -> str:
    text = SRC.read_text(encoding="utf-8")
    start = text.index("export async function resumeStream(")
    end = text.index("\n  export ", start + 10)
    return text[start:end]


def test_first_token_no_longer_removes_the_spinner():
    body = _resume_body()
    delta_branch = body[body.index("} else if (json.delta) {"):body.index("} else if (json.type === 'doc_stream_open')")]
    assert "spinner.destroy()" not in delta_branch
    assert "_resumeStatus(json.thinking ? 'Thinking' : 'Writing answer')" in delta_branch


def test_steps_and_tools_update_the_status():
    body = _resume_body()
    assert "json.type === 'agent_step' && json.round" in body
    assert "_resumeStatus(_resumeToolLabel(json.tool))" in body
    assert "spinner.updateMessage(" in body


def test_spinner_still_removed_when_the_run_ends():
    body = _resume_body()
    cleanup = body[body.index("const cleanup = () => {"):]
    assert "spinner.destroy()" in cleanup[:200]
