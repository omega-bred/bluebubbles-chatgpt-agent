# Spring Boot 4 and Spring AI 2 Tool Search Design

## Goal

Upgrade PR #161 from the archived Spring AI Community tool-search artifacts to the supported Spring AI 2 implementation while preserving the PR's progressive tool-disclosure behavior. The application will move from Spring Boot 3.5.14 and Spring AI 1.1.4 to Spring Boot 4.1.0 and Spring AI 2.0.0.

The upgrade must not merge PR #161. It will update the existing PR branch only after local verification succeeds.

## Current State

The application sends OpenAI Responses API requests directly through `openai-java`; it does not use Spring AI `ChatClient` or its advisor loop. PR #161 currently implements progressive tool disclosure inside `AgentToolRegistry` and `BBMessageAgent`, using these archived dependencies only as a Lucene search abstraction:

- `org.springaicommunity:tool-search-tool:1.0.1`
- `org.springaicommunity:tool-searcher-lucene:1.0.1`

The application is already on the latest Spring Boot 3.5 maintenance release required as the starting point for a Boot 4 migration. Its Java 25 toolchain is within Spring Boot 4.1's supported Java range.

## Dependency Design

The build will use:

- Spring Boot Gradle plugin `4.1.0`
- Spring AI BOM `2.0.0`
- `org.springframework.ai:spring-ai-tool-search-tool`
- Spring Boot Admin client `4.1.2`
- Boot 4's Jackson 2 compatibility module as a deliberate transition bridge

Both Spring AI Community tool-search artifacts will be removed. Dependency resolution must show no `org.springaicommunity` tool-search modules and exactly one Spring AI version line.

Boot 4 defaults toward Jackson 3, but this repository has 86 main/test source files using Jackson 2 packages, plus generated OpenAPI and third-party Jackson 2 clients. This migration will retain Jackson 2 with Boot's supported compatibility module rather than mixing a platform upgrade with an application-wide serialization rewrite. A later focused migration can remove that bridge.

## Tool Search Architecture

`ToolSearchAgentTool` will retain the existing application-level contract:

1. The model initially receives only `toolSearchTool`.
2. The tool searches the transport- and account-filtered registry.
3. Matching tool names are returned.
4. Only those definitions are added to the following Responses API request.
5. The selected action tool is executed through the existing registry.

The search implementation will move to Spring AI 2 packages:

- `org.springframework.ai.tool.toolsearch.ToolReference`
- `org.springframework.ai.tool.toolsearch.ToolSearchRequest`
- `org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex`

The application will continue to own orchestration because replacing the direct Responses API flow with Spring AI `ChatClient` and `ToolSearchToolCallingAdvisor` would be a separate agent-runtime rewrite. The official `LuceneToolIndex` will replace only the archived search implementation.

The existing per-invocation index lifecycle will be preserved initially: create an in-memory index, add the already-filtered tool summaries, search, and close it. This avoids cross-account or cross-transport cache leakage. Any persistent index optimization requires separate profiling and design.

## Spring Boot 4 Compatibility Scope

Only compatibility changes required to compile, start, and test on Boot 4 are in scope:

- Adopt Boot 4-compatible starter/module names where required.
- Move `org.springframework.lang.Nullable` and `javax.annotation.Nullable` usage to JSpecify where required by Spring Framework 7.
- Update explicitly versioned Spring ecosystem integrations when their current versions are Boot 3-only.
- Update tests for Boot 4's testing module split or annotation behavior where required.
- Update configuration properties only when the Boot migrator or startup validation identifies a concrete rename or removal.

The migration will not change REST APIs, persistence schemas, account identity behavior, billing behavior, tool schemas, or agent prompts except where the official tool-search API requires an equivalent representation.

If a third-party dependency has no Boot 4-compatible release, implementation stops with the exact blocker rather than removing the feature or substituting a different service.

## Error Handling and Safety

Tool-search input validation remains unchanged. Blank queries return an empty list, result counts remain clamped, category filtering stays application-owned, and search/index failures return an empty tool list rather than breaking message processing.

The migration will preserve account and transport filtering before indexing. Tests must continue proving that unauthorized Kubernetes tools and BlueBubbles-only tools are not discoverable from the wrong account or LXMF transport.

No production deployment, PR merge, database migration, or external service mutation is part of this work. The only remote write is updating the existing PR branch after verification.

## Verification

Verification will proceed in increasing scope:

1. Resolve and inspect the Boot 4/Spring AI 2 dependency graph.
2. Compile main and test sources.
3. Run the tool-search registry, context derivation, BlueBubbles agent, and LXMF end-to-end tests.
4. Run `spotlessApply` followed by `spotlessCheck`.
5. Run the full Gradle test suite and classify only known live-service failures separately.
6. Build the executable Boot jar and start the application far enough to validate the Spring context and Boot 4 configuration.
7. Re-run the local Lucene benchmark and schema-size measurement.
8. With approved 1Password access, run a side-effect-free live model smoke comparing progressive disclosure with the full-tool baseline, recording tool-selection correctness and end-to-end latency.
9. Push with an exact force-with-lease only after refreshing the live `main` and PR branch SHAs.

GitHub Actions is currently experiencing an outage, so queued remote checks will be reported as an external gate rather than treated as passing or failing.

## Success Criteria

The migration is ready for review when:

- The application builds on Spring Boot 4.1.0 and Spring AI 2.0.0.
- Tool search uses only the official Spring AI artifact and package names.
- Existing progressive-disclosure, authorization, transport, and natural-language discovery tests pass.
- The application context starts successfully with Boot 4.
- Dependency inspection finds no archived Spring AI Community tool-search artifact or mixed Spring AI version line.
- Local search overhead remains negligible relative to model latency, and the live smoke does not reveal a correctness regression.
- PR #161 is updated but remains unmerged pending explicit user approval.

## References

- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 4.1 System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring AI 2 Dynamic Tool Discovery](https://docs.spring.io/spring-ai/reference/guides/dynamic-tool-search.html)
- [Spring AI 2 Tool Search Reference](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/tools/tool-search-tool.html)
