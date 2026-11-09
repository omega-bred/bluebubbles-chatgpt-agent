This overrides OpenAPI Generator 7.27.0's `typescript-axios/tsconfig.esm.mustache`.

The upstream ESM configuration leaves module resolution unspecified. With the
pinned generated-client compiler (TypeScript 5.9.3), `module: esnext` defaults to
Classic resolution and cannot resolve Axios. Bundler resolution supports the
ESNext output consumed by the frontend while keeping the upstream CommonJS build.

The former `common.mustache` override is removed: Generator 7.27.0 includes its
explicit `Promise<R>` return type and cast unchanged, so Axios declaration builds
retain the TS2527 fix through the upstream template.

Upstream ESM template: https://github.com/OpenAPITools/openapi-generator/blob/v7.27.0/modules/openapi-generator/src/main/resources/typescript-axios/tsconfig.esm.mustache
