# Workspace instructions and SOUL

Android chat sends workspace instructions through each provider's native system channel in this application order:

1. The discovered AGENTS.md / CLAUDE.md workspace chain.
2. The global SOUL.md application prompt.
3. Generated identity, style, tool guidance, skills, memory and runtime context.

The per-turn configuration snapshot reads the workspace chain before loading SOUL.md. Retries, fallback providers and mid-turn model switches reuse the frozen application block. Workspace guidance takes precedence over the other application sections; more specific workspace files take precedence within the workspace chain. This ordering does not override service-side rules, safety requirements or device permissions.

The global file is `/var/minis/memory/AGENTS.md`. Project files are discovered between the project root and working directory configured in Settings → Workspace Instructions. Saving a file applies to the next turn. The fixed priority header is outside the configurable per-chain file budget, so long-file truncation cannot remove the application ordering. Missing files produce no empty workspace block.

Chat Completions uses the leading system message; Responses uses `instructions`; Anthropic uses the `system` array; Gemini text models use `systemInstruction`. Anthropic OAuth retains its required provider identity prefix before application content. The ordinary Anthropic API-key path does not require OAuth identity configuration. Gemini audio-only models that reject system instructions keep their existing provider restriction.

The compact chat composer displays `Off`, `Low`, `Medium`, `High`, `XHigh`, `Max`, `Ultra` from the same display names as the effort slider. Request values and serialization remain unchanged.

Regression coverage includes real workspace rendering with UTF-8 and small budgets, per-turn source wiring, provider system-channel placement, actual request serialization for OpenAI/Anthropic API-key/Gemini, and composer static checks. OAuth serialization is exercised only when its build-time identity is configured; an unconfigured public build explicitly skips that one test. No test sends a model request.
