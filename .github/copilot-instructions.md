# GitHub Copilot Instructions

This repository is the `ai-agent` Java/Spring AI Agent execution service. Before suggesting or changing code, read `AGENTS.md` and `docs/ai-coding/README.md`.

Follow these project rules:

- Follow `docs/ai-coding/AI_DIRECTORY_STRUCTURE_GUIDE.md` before adding, moving, or deleting directories.
- Keep Java code under `src/main/java/com/ai/agent`; tests belong under `src/test/java/com/ai/agent`.
- Keep model selection through `AiChatClientProvider`; do not bind controllers directly to one provider SDK.
- Do not move main `ai` service session, memory, audit, questionnaire persistence, or file-token ownership into this service.
- Do not add trading, K-line, order, account, market, Discord, or Apple subscription directories to this service.
- Do not change existing model keys, Nacos namespace/group, or production configuration values. Report file paths and line numbers only.
