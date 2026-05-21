Inspect the repository for opportunities to simplify and reduce code without changing behavior.

Focus on high-confidence cleanup only:
- Remove duplicative methods or consolidate them into shared helpers.
- Replace custom implementations of common behavior with standard library or established project libraries, such as Apache Commons Lang StringUtils where appropriate.
- Remove unnecessary, misleading, or overly broad try/catch logic.
- Reduce total code volume when it improves clarity.
- Commonize repeated patterns across modules.
- Improve abstractions where the same concept is wired repeatedly in multiple places.
- Reduce unnecessary classes, wrappers, factories, adapters, or indirection when they do not add meaningful value.
- Reduce complexity and unnecessary wiring.
- Reduce "hard parsing" of things (eg prefer typed classes when serializing or deserializing json, arbitrary map construction should be avoided in favor of a strongly modeled class. Either a pojo or an object in the OpenAPI spec as appropriate.)
- Reduce cyclomatic complexity 
- Commonize constants (remove repeated literals in favor of shared constants) 

In addition, if you are able to access the `bdawg-3646` kube cluster - and the bluebubbles-chatgpt-agent namespace is available - look through recnet logs to see if there are any fixable or correctable errors and try to fix those. You'll need to test access outside of your sandbox unfortunately. 

Constraints:
- Preserve behavior.
- Keep changes small, reviewable, and easy to revert.
- Prefer existing project conventions over new architectural patterns.
- Do not perform broad rewrites or speculative refactors.
- Do not change public APIs unless the simplification is clearly safe and all call sites are updated.
- Do not remove error handling that protects real failure modes.
- Add or update tests only when needed to protect the cleanup.

Workflow:
0. Check that you're on `main` and have pulled recently. If you're too far behind (eg more than a day old) - abort the cleanup. 
1. Scan for the best cleanup opportunity.
2. Choose one focused change with a strong simplicity payoff.
3. Implement it.
4. Run relevant tests, formatting, and static checks.
5. Summarize what was simplified, why it is safe, and how much code or complexity was removed.

If no safe cleanup is found, report the top candidates and why they were left untouched.

When you do find a cleanup or simpliciation or have changes that fit the goal - push to a new branch and publish a draft PR on github.
