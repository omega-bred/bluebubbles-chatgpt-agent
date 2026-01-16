Any time you add or change rest APIs - always use the src/main/resources/openapi.yaml file to make the changes and run the ./gradlew openApiGenerate command to get the newly added classes / method stubs. Never directly add the raw APIs. 

The web app should always use the generated ts client. 

The API paths are in the style of `/api/v1/$resourceType/$verb.$resource(s). 

Formatting should always be run via ./gradlew spotlessApply

