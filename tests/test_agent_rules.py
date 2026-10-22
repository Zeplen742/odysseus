from types import SimpleNamespace
from unittest.mock import MagicMock

from src import agent_rules
from src.chat_processor import ChatProcessor


def _preface(**kwargs):
    processor = ChatProcessor(memory_manager=MagicMock(), personal_docs_manager=MagicMock())
    session = SimpleNamespace(endpoint_url="http://local", model="test", headers={})
    preface, _, _ = processor.build_context_preface(
        message="hello", session=session, use_web=False, use_rag=False,
        use_memory=False, use_skills=False, **kwargs,
    )
    return preface


def _system_texts(preface):
    return [m["content"] for m in preface if m["role"] == "system"]


def test_rules_file_becomes_system_message(tmp_path, monkeypatch):
    rules = tmp_path / "rules.md"
    rules.write_text("RULE: save files in the workspace.\n", encoding="utf-8")
    monkeypatch.setenv("ODYSSEUS_AGENT_RULES_FILE", str(rules))

    assert "RULE: save files in the workspace." in _system_texts(_preface())


def test_rules_come_after_preset_and_survive_without_one(tmp_path, monkeypatch):
    rules = tmp_path / "rules.md"
    rules.write_text("RULE", encoding="utf-8")
    monkeypatch.setenv("ODYSSEUS_AGENT_RULES_FILE", str(rules))

    with_preset = _system_texts(_preface(preset_system_prompt="PERSONA"))
    assert with_preset.index("PERSONA") < with_preset.index("RULE")
    assert "RULE" in _system_texts(_preface(preset_system_prompt=None))


def test_edits_apply_on_next_turn(tmp_path, monkeypatch):
    rules = tmp_path / "rules.md"
    rules.write_text("OLD", encoding="utf-8")
    monkeypatch.setenv("ODYSSEUS_AGENT_RULES_FILE", str(rules))
    assert "OLD" in _system_texts(_preface())

    rules.write_text("NEW", encoding="utf-8")
    texts = _system_texts(_preface())
    assert "NEW" in texts and "OLD" not in texts


def test_missing_or_blank_file_adds_nothing(tmp_path, monkeypatch):
    monkeypatch.setenv("ODYSSEUS_AGENT_RULES_FILE", str(tmp_path / "absent.md"))
    baseline = _preface()

    blank = tmp_path / "blank.md"
    blank.write_text("  \n\n", encoding="utf-8")
    monkeypatch.setenv("ODYSSEUS_AGENT_RULES_FILE", str(blank))

    assert agent_rules.load_agent_rules() == ""
    assert _preface() == baseline


def test_oversized_file_is_truncated(tmp_path, monkeypatch):
    rules = tmp_path / "rules.md"
    rules.write_text("x" * (agent_rules.MAX_RULES_CHARS + 500), encoding="utf-8")
    monkeypatch.setenv("ODYSSEUS_AGENT_RULES_FILE", str(rules))

    assert len(agent_rules.load_agent_rules()) == agent_rules.MAX_RULES_CHARS


def test_default_path_is_in_data_dir(monkeypatch):
    monkeypatch.delenv("ODYSSEUS_AGENT_RULES_FILE", raising=False)
    assert agent_rules.rules_path().endswith("agent_rules.md")
    assert agent_rules.rules_path().startswith(agent_rules.DATA_DIR)
