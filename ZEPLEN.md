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
| feat: vision models see tool images (llama.cpp vision check, read_file on images, image token estimate) | upstream asks llama.cpp whether a model has a projector, and read_file returns images |
| fix(chat): naming a reachable path no longer locks the agent into that folder | upstream skips the message-path workspace bind for paths the default tool roots already allow (`routes/chat_routes.py`) |
| feat: vision models see tool images (llama.cpp vision check, read_file on images) | upstream sends tool-returned images to vision models and asks llama.cpp whether a model has a projector |
| fix(context): trimming keeps the user request in long agent turns | a release includes upstream dev's latest-user anchor in `trim_for_context` (backported from dev) |
| ci: build this fork's image | never; it is how the image is built |

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
