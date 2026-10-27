"""A chat run re-attached with resumeStream() must show the Stop button.

Reopening a chat on a second device (a phone) while a job started elsewhere is still
running attaches through resumeStream(). It used to leave the send button in its idle
state (send arrow / "+ New"), so there was no way to stop the run from that device.
chat.js needs browser globals and can't be imported under node; this guards the fix
at the source level.
"""
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "static/js/chat.js"


def _resume_body() -> str:
    text = SRC.read_text(encoding="utf-8")
    start = text.index("export async function resumeStream(")
    return text[start:start + 6000]


def test_resume_stream_switches_button_to_stop_and_back():
    body = _resume_body()
    assert "updateSubmitButton('streaming', _resumeBtn)" in body
    assert "updateSubmitButton('idle', _resumeBtn)" in body
