This overrides OpenAPI Generator 7.22.0's `typescript-axios/common.mustache`.

It backports the explicit `Promise<R>` return type and cast from upstream 7.25.0
to prevent TS2527 when generating declarations with Axios 1.20.0. Axios now uses
a private unique-symbol default for its response type; inferring the helper return
type leaks that inaccessible symbol into declarations. Runtime request behavior
and the public response generic remain unchanged.

Remove this override after upgrading the generator to a version with the fix.

Upstream template: https://github.com/OpenAPITools/openapi-generator/blob/v7.25.0/modules/openapi-generator/src/main/resources/typescript-axios/common.mustache
