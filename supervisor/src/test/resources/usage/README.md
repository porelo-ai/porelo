These answer fixtures are written from the providers' documentation, not recorded from live answers. Names, counts and private sentinels are synthetic.

- `chat.json` and `chat.sse`: https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
- `responses.json` and `responses.sse`: https://developers.openai.com/api/reference/resources/responses/methods/create
- `messages.json`, `messages.sse` and the deliberately cut `messages-aborted.sse`: https://platform.claude.com/docs/en/build-with-claude/streaming
- `gemini.json` and `gemini.sse`: https://ai.google.dev/api/generate-content

The stream fixtures exercise cumulative counts, fragmented tool names and final usage. A stand-in provider replays them byte for byte.
