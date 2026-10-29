"""Local patch: MCP tool schemas are read under the 1.x and 2.x SDK field names."""

from types import SimpleNamespace

from src.mcp_manager import _tool_input_schema

SCHEMA = {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]}


class _Model:
    def __init__(self, data):
        self._data = data

    def model_dump(self, by_alias=False, exclude_none=False):
        return dict(self._data)


def test_sdk_1x_field_name():
    assert _tool_input_schema(SimpleNamespace(inputSchema=SCHEMA)) == SCHEMA


def test_sdk_2x_field_name():
    # mcp 2.x: input_schema, with inputSchema only as the wire alias
    assert _tool_input_schema(SimpleNamespace(input_schema=SCHEMA)) == SCHEMA


def test_sdk_2x_pydantic_schema_object():
    assert _tool_input_schema(SimpleNamespace(input_schema=_Model(SCHEMA))) == SCHEMA


def test_missing_schema_is_empty():
    assert _tool_input_schema(SimpleNamespace(name="x")) == {}
