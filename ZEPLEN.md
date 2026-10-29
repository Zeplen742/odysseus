# zeplen branch

This branch is upstream Odysseus release 1.0.3 (`main` at 934d23c) plus a small
set of patches, built into `ghcr.io/zeplen742/odysseus` by
`.github/workflows/zeplen-image.yml`.

Each patch is one commit, so the branch can be rebased onto the next release:

| Commit | Drop when |
|---|---|
| fix(agent): pin file and web tools every turn | a release carries stateful file/web tools across vague follow-ups (#4365) |
| fix(context): let the environment override a model's context size | a release detects Ollama context sizes (#5613, PR #5800) |
| feat: operator rules file sent every turn | upstream gains an always-on instructions setting |
| fix(chat): bare current/latest/today/rate no longer strip file tools | upstream's explicit-web-intent regex stops matching bare words (`routes/chat_routes.py`) |
| perf(context): trimming keeps the prompt prefix stable; rules never trimmed | upstream trims in blocks/stubs (or caches the cut) and protects the rules file (`context_compactor.py`, `chat_processor.py`) |
| fix(chat): naming a reachable path no longer locks the agent into that folder | upstream skips the message-path workspace bind for paths the default tool roots already allow (`routes/chat_routes.py`) |
| feat: vision models see tool images (llama.cpp vision check, read_file on images) | upstream sends tool-returned images to vision models and asks llama.cpp whether a model has a projector |
| fix(context): trimming keeps the user request in long agent turns | a release includes upstream dev's latest-user anchor in `trim_for_context` (backported from dev) |
| perf(chat): saved tool output and diffs render when expanded | upstream renders saved agent threads lazily (`static/js/chatRenderer.js`) |
| fix(chat): a re-attached run shows the Stop button | upstream's resumeStream() sets the send button to its streaming state (`static/js/chat.js`) |
| fix(agent): llama.cpp LFM models get native tools | upstream's `model_supports_tools` recognises LFM models (`src/agent_loop.py`) |
| fix(agent): loop-breaker compares whole tool calls, not a 120-char prefix | upstream's loop-breaker signature covers the whole call (`src/agent_loop.py`) |
| feat: ODYSSEUS_DISABLE_BROWSER_MCP skips only the browser MCP server | upstream can turn off one built-in MCP server (`src/builtin_mcp.py`) |
| feat(agent): tools on request, round status line, tool schemas in the trim budget | upstream loads tools on demand, tells the model its round/context use, and budgets tool schemas (`src/tool_groups.py`, `src/agent_loop.py`) |
| build: pip may add packages but never change the image's versions | never; local need (`Dockerfile`, PIP_CONSTRAINT) |
| fix(chat): a re-attached run keeps a live step/tool status | upstream's resumeStream() shows progress after the first token (`static/js/chat.js`) |
| fix: the app and built-in MCP servers ignore user-site packages | upstream stops the app loading packages the agent installs with `pip --user` (`src/user_site_guard.py`, `app.py`) |
| fix(mcp): read tool input schemas under the mcp 1.x and 2.x field names | upstream reads `input_schema` as well as `inputSchema` (`src/mcp_manager.py`) |
| debug: ODYSSEUS_DEBUG_MCP_ARGS logs MCP schemas and call arguments; pin Playwright MCP 0.0.83 | the empty-arguments browser bug is found and fixed (drop the logging; keep a pin) |
| build: 3D modelling libraries in the image | never; local need (`requirements-3d.txt`, `Dockerfile`) |
| ci: build this fork's image | never; it is how the image is built |

## Agent flags (all off by default)

- `ODYSSEUS_TOOLS_ON_REQUEST=1`: an agent turn starts with the always-available
  tools plus the groups the chat already loaded; the model calls `load_tools`
  for anything else (groups in `src/tool_groups.py`). No retrieval guess, so a
  vague "continue" no longer drags in ~30 browser tools.
- `ODYSSEUS_ROUND_STATUS=1`: each round's last tool result ends with
  `[odysseus status: step N of M · context X% full · ...]`. Facts only; what
  to do at each flag belongs in the rules file.
- `ODYSSEUS_TRIM_COUNTS_TOOLS=1`: the tool schemas sent with a request come
  out of the trim budget, so a context override can be the served window minus
  room for the reply instead of a hand-tuned guess at schema size.

- `ODYSSEUS_DEBUG_MCP_ARGS=1`: logs `[mcp-debug]` lines for MCP tools only
  (first 200 chars): the schema sent for `browser_navigate` and any MCP tool
  with empty parameters, the arguments as assembled from the stream, as
  converted, and as passed to the MCP server.

- `ODYSSEUS_ALLOW_USER_SITE=1`: let the app and built-in MCP servers load
  user-site packages again (off by default since 2026-10-29; the agent's own
  shell and Python runs always see them).

## Rules file

`data/agent_rules.md` (or the path in `ODYSSEUS_AGENT_RULES_FILE`) is added as
a system message on every chat and agent turn, after any preset or persona.
It is read on each request, so edits apply without a restart. Up to 8000
characters; a missing or empty file adds nothing.

## Updating to a new release

1. `git fetch origin && git rebase --onto origin/main 934d23c zeplen`
   (replace 934d23c with the previous release commit), dropping patches
   upstream has made unnecessary.
2. Run the tests, push, wait for the image build.
3. Point the deployment at the new `:<version>-zeplen.<sha>` tag.
