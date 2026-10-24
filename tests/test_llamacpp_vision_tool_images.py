"""Local patch: vision models see tool-returned images.

llama.cpp reports whether a model has a vision projector (router launch args,
or /props on a single-model server); read_file on an image returns the image;
the agent loop attaches tool images for models that can see them.
"""
import asyncio
import base64

import pytest

from src import chat_helpers
from src.agent_loop import _append_tool_results, _tool_images_message
from src.agent_tools.filesystem_tools import ReadFileTool
from src.context_compactor import trim_for_context
from src.model_context import IMAGE_TOKEN_ESTIMATE, estimate_tokens


class _FakeResponse:
    def __init__(self, payload, ok=True):
        self._payload = payload
        self.is_success = ok

    def json(self):
        return self._payload


ROUTER_MODELS = {"object": "list", "data": [
    {"id": "qwen3.8-27b", "aliases": [], "owned_by": "llamacpp",
     "status": {"value": "unloaded", "args": [
         "/usr/bin/llama-server", "--model", "/m/qwen.gguf", "--mmproj", "/m/mmproj.gguf"]}},
    {"id": "glm-4.7-flash", "aliases": [], "owned_by": "llamacpp",
     "status": {"value": "loaded", "args": ["/usr/bin/llama-server", "--model", "/m/glm.gguf"]}},
    {"id": "qwen3.8-27b-iq3", "aliases": [], "owned_by": "llamacpp",
     "status": {"value": "unloaded", "args": ["--model", "/m/q.gguf", "--no-mmproj"]}},
]}
SINGLE_MODELS = {"object": "list", "data": [{"id": "my-model", "owned_by": "llamacpp"}]}
URL = "http://192.168.68.60:8080/v1"


@pytest.fixture(autouse=True)
def _clear_caches():
    chat_helpers._llamacpp_models_cache.clear()
    chat_helpers._lmstudio_models_cache.clear()
    yield
    chat_helpers._llamacpp_models_cache.clear()
    chat_helpers._lmstudio_models_cache.clear()


def _serve(monkeypatch, models, props=None):
    calls = []

    def fake_get(url, timeout=None):
        calls.append(url)
        if url.endswith("/v1/models"):
            return _FakeResponse(models)
        if url.endswith("/props"):
            return _FakeResponse(props or {})
        return _FakeResponse({}, ok=False)  # LM Studio's /api/v1/models

    monkeypatch.setattr(chat_helpers.httpx, "get", fake_get)
    return calls


class TestLlamacppSupportsVision:
    def test_router_model_with_mmproj_sees(self, monkeypatch):
        _serve(monkeypatch, ROUTER_MODELS)
        assert chat_helpers.llamacpp_supports_vision(URL, "qwen3.8-27b") is True

    def test_router_model_without_mmproj_is_blind(self, monkeypatch):
        _serve(monkeypatch, ROUTER_MODELS)
        assert chat_helpers.llamacpp_supports_vision(URL, "glm-4.7-flash") is False
        assert chat_helpers.llamacpp_supports_vision(URL, "qwen3.8-27b-iq3") is False

    def test_router_answers_without_props(self, monkeypatch):
        # /props on a router loads the model; launch args answer without it.
        calls = _serve(monkeypatch, ROUTER_MODELS)
        chat_helpers.llamacpp_supports_vision(URL, "qwen3.8-27b")
        assert not any(c.endswith("/props") for c in calls)

    def test_single_server_reads_props(self, monkeypatch):
        _serve(monkeypatch, SINGLE_MODELS, {"modalities": {"vision": True, "audio": False}})
        assert chat_helpers.llamacpp_supports_vision(URL, "my-model") is True

    def test_non_llamacpp_endpoint_returns_none(self, monkeypatch):
        _serve(monkeypatch, {"data": [{"id": "qwen3:8b", "owned_by": "library"}]})
        assert chat_helpers.llamacpp_supports_vision(URL, "qwen3:8b") is None

    def test_remote_host_is_never_probed(self, monkeypatch):
        calls = _serve(monkeypatch, ROUTER_MODELS)
        assert chat_helpers.llamacpp_supports_vision("https://api.example.com/v1", "qwen3.8-27b") is None
        assert calls == []

    def test_model_supports_vision_uses_llamacpp(self, monkeypatch):
        # "qwen3.8-27b" has no vision keyword, so name matching alone says no.
        assert chat_helpers.is_vision_model("qwen3.8-27b") is False
        _serve(monkeypatch, ROUTER_MODELS)
        assert chat_helpers.model_supports_vision("qwen3.8-27b", URL) is True
        assert chat_helpers.model_supports_vision("glm-4.7-flash", URL) is False


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


class TestReadFileImage:
    def test_png_returns_image(self, tmp_path, monkeypatch):
        p = tmp_path / "render.png"
        p.write_bytes(PNG)
        monkeypatch.setattr("src.tool_execution._resolve_tool_path", lambda raw: raw)
        result = asyncio.run(ReadFileTool().execute(str(p), {}))
        assert result["exit_code"] == 0
        assert result["images"] == [{"data": base64.b64encode(PNG).decode(), "mimeType": "image/png"}]
        assert "image/png" in result["output"] and "\x89PNG" not in result["output"]

    def test_text_file_unchanged(self, tmp_path, monkeypatch):
        p = tmp_path / "notes.md"
        p.write_text("hello")
        monkeypatch.setattr("src.tool_execution._resolve_tool_path", lambda raw: raw)
        result = asyncio.run(ReadFileTool().execute(str(p), {}))
        assert result == {"output": "hello", "exit_code": 0}


def _records(images):
    return [{"tool_name": "read_file", "content": "/w/render.png",
             "result": {"output": "[image]", "images": images, "exit_code": 0}, "text": "[image]"}]


IMG = {"data": "QUJD", "mimeType": "image/png"}


class TestToolImagesReachTheModel:
    def _run(self, attach):
        messages = [{"role": "user", "content": "engrave it"}]
        calls = [{"id": "call_1", "name": "read_file", "arguments": "{}"}]
        _append_tool_results(messages, "", calls, ["[image]"], ["[image]"], True, 1,
                             tool_result_records=_records([IMG]), attach_images=attach)
        return messages

    def test_attached_for_vision_models(self):
        last = self._run(True)[-1]
        assert last["role"] == "user"
        assert last["metadata"]["trusted"] is False
        parts = last["content"]
        assert parts[0]["type"] == "text"
        assert parts[1] == {"type": "image_url", "image_url": {"url": "data:image/png;base64,QUJD"}}

    def test_not_attached_otherwise(self):
        messages = self._run(False)
        assert [m["role"] for m in messages] == ["user", "assistant", "tool"]

    def test_caps_images_per_round(self):
        msg = _tool_images_message(_records([IMG] * 9))
        assert sum(p["type"] == "image_url" for p in msg["content"]) == 4

    def test_no_images_no_message(self):
        assert _tool_images_message(_records([])) is None

    def test_image_message_does_not_replace_the_request_when_trimming(self):
        messages = [{"role": "system", "content": "sys"}, {"role": "user", "content": "Engrave TRUELINE."}]
        for i in range(12):
            calls = [{"id": f"c{i}", "name": "read_file", "arguments": "{}"}]
            _append_tool_results(messages, "", calls, ["x " * 400], ["x " * 400], True, i,
                                 tool_result_records=_records([IMG]), attach_images=True)
        trimmed = trim_for_context(messages, context_length=6000, reserve_tokens=512)
        assert any(m.get("content") == "Engrave TRUELINE." for m in trimmed)


def test_estimate_counts_images():
    text_only = [{"role": "user", "content": [{"type": "text", "text": "abc"}]}]
    with_image = [{"role": "user", "content": [{"type": "text", "text": "abc"},
                                               {"type": "image_url", "image_url": {"url": "data:x"}}]}]
    assert estimate_tokens(with_image) - estimate_tokens(text_only) == IMAGE_TOKEN_ESTIMATE
