# SOUL: the sole application instruction source

Settings → System Prompt opens the SOUL editor directly, without another directory row; Back returns to Settings. AGENTS.md / CLAUDE.md discovery, project chains, scope/budget controls and their editors are removed. Legacy workspace deep links open the SOUL editor; existing user files and preferences are left on disk, not silently merged, overwritten or deleted.

The SOUL Markdown body inherits the former AGENTS authority wording and native-system format:

1. The fixed request-authority header, now identifying SOUL (including truthful failure reporting).
2. `Instructions from: /var/minis/memory/SOUL.md`, a blank line and the exact configured body.
3. Generated tool/skill/memory/runtime guidance in the existing provider tail.

A nonblank body is exclusive: no second SOUL wrapper, generated identity or legacy metadata style follows it. Name/avatar remain UI metadata. An empty body emits no authority block and retains the usual identity/environment fallback. Body content is not line-filtered, escaped, trimmed, truncated or sent as a fake user reminder. The SOUL frontmatter storage format and avatar sidecar are unchanged.

The body is snapshotted once per turn. Retries, fallback providers and mid-turn model switches reuse that exact block; child agents retain their isolated task prompt. The memory toggle does not turn SOUL off. Settings edits apply on the next turn. Sensitive `minis-config soul.body` writes still require user approval.

Chat Completions uses its first system message; Responses uses leading `instructions`; Anthropic uses the `system` array; Gemini uses `systemInstruction`. Anthropic OAuth retains the provider-required identity block first, then SOUL, then runtime. Gemini audio-only restrictions remain unchanged. Request wording cannot change server enforcement or Android permission gates.

Regression coverage: SoulInstructionRenderTest, ApplicationSystemPromptTest, SoulBodyLimitPolicyTest, SystemPromptInjectionWiringTest, SoulSystemChannelTest and SystemChannelPlacementTest. Provider body tests construct JSON without network requests. The optional OAuth test retains its existing public-build skip when no identity is configured.
