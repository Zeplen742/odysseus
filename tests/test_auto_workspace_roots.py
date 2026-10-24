"""Local patch: naming a path the tools can already reach doesn't bind a workspace.

"Read <project>/kit/README.md, then save to the agent workspace" used to bind
the kit folder as the workspace, confining every file tool to it, so the agent
couldn't save where it was told and left its files in the project folder.
"""
import importlib

from routes import chat_routes


def _roots(monkeypatch, *roots):
    # By name: other tests reload src.tool_execution, and the code under test
    # imports whichever module object is current.
    monkeypatch.setattr("src.tool_execution._tool_path_roots", lambda: [str(r) for r in roots])


def test_path_within_tool_roots(tmp_path, monkeypatch):
    project, ws, other = tmp_path / "Claude", tmp_path / "ws", tmp_path / "elsewhere"
    for d in (project / "kit", ws, other):
        d.mkdir(parents=True)
    _roots(monkeypatch, project, ws)
    within = importlib.import_module("src.tool_execution").path_within_tool_roots
    assert within(str(project / "kit"))
    assert within(str(ws))
    assert not within(str(other))
    assert not within(str(tmp_path / "Claude-extra"))


def _admin(monkeypatch):
    monkeypatch.setattr(chat_routes, "get_current_user", lambda _request: "admin")
    monkeypatch.setattr("src.tool_security.owner_is_admin_or_single_user", lambda _user: True)


def test_reachable_path_escalates_without_binding(tmp_path, monkeypatch):
    kit, ws = tmp_path / "Claude" / "kit", tmp_path / "ws"
    kit.mkdir(parents=True)
    ws.mkdir()
    (kit / "README.md").write_text("kit")
    _roots(monkeypatch, tmp_path / "Claude", ws)
    _admin(monkeypatch)
    message = f"Read {kit}/README.md, then engrave it and save the result to {ws}/."
    assert chat_routes._auto_workspace_from_message(None, message) == ("", True)


def test_unreachable_path_still_binds(tmp_path, monkeypatch):
    project = tmp_path / "project"
    project.mkdir()
    (project / "main.py").write_text("print(1)")
    _roots(monkeypatch, tmp_path / "ws")
    _admin(monkeypatch)
    message = f"Fix the bug in {project}/main.py"
    assert chat_routes._auto_workspace_from_message(None, message) == (str(project), True)


def test_no_path_no_escalation(monkeypatch):
    monkeypatch.setattr(chat_routes, "_resolve_workspace_from_message_path", lambda *_a: ("", ""))
    assert chat_routes._auto_workspace_from_message(None, "what's the weather?") == ("", False)
