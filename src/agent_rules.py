"""Operator rules sent to the model on every turn.

The text of ``agent_rules.md`` in the data directory (or the file named by
ODYSSEUS_AGENT_RULES_FILE) becomes a system message on every chat and agent
turn, independent of presets, personas and new-chat resets. The file is read
on each request, so edits apply without a restart. A missing or empty file
adds nothing.
"""
import logging
import os

from src.constants import DATA_DIR

logger = logging.getLogger(__name__)

MAX_RULES_CHARS = 8000


def rules_path() -> str:
    return os.getenv("ODYSSEUS_AGENT_RULES_FILE") or os.path.join(DATA_DIR, "agent_rules.md")


def load_agent_rules() -> str:
    """Return the rules text, or "" if there is no usable rules file."""
    path = rules_path()
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read(MAX_RULES_CHARS + 1)
    except FileNotFoundError:
        return ""
    except (OSError, UnicodeDecodeError) as e:
        logger.warning("Could not read agent rules file %s: %s", path, e)
        return ""
    if len(text) > MAX_RULES_CHARS:
        logger.warning("Agent rules file %s is over %d characters; using the first %d",
                       path, MAX_RULES_CHARS, MAX_RULES_CHARS)
        text = text[:MAX_RULES_CHARS]
    return text.strip()
