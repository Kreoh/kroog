# Adding models

1. Start with the closest merged model PR. Follow how it registered the model, exposed it to callers and tested it. [PR #8](https://github.com/Kreoh/kroog/pull/8) is an example for OpenAI and Vertex.
2. Check the provider docs for the exact model and API. Use `ModelCatalogue.find(id, api)` to verify the limits and capabilities callers see, including any differences between streaming and non-streaming requests.
3. Run focused JVM tests through the public client or catalogue lookup. Pick checks that exercise the behaviour you claim to support, and state in the PR which checks used a live provider.
