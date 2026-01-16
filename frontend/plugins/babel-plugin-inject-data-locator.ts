import type {NodePath} from "@babel/traverse";
import type * as BabelTypes from "@babel/types";
import type {RawSourceMap} from "source-map-js"; // Added RawSourceMap type

import {SourceMapConsumer} from "source-map-js";
import {threeJsComponents} from "./threejs-components";

// Define types for Babel file and state objects
interface BabelFile {
  opts: {
    filename?: string;
    [key: string]: unknown;
  };
}

interface BabelState {
  file: BabelFile;
  [key: string]: unknown;
}

// Define a type for error objects
type ErrorWithMessage = Error & {
  message: string;
};

interface CustomPluginOptions {
  inputSourceMap?: RawSourceMap | string; // Specify type for inputSourceMap
  types: typeof BabelTypes;
}

// Context tracking for Three.js components
interface ContextInfo {
  isThreeJsContext: boolean;
  depth: number;
}

// Helper to create the plugin object, allows cleaner passing of `t` and options
const createPluginLogic = (babel: {types: typeof BabelTypes}, options: CustomPluginOptions) => {
  const t = babel.types;
  const consumerHolder: {consumer?: SourceMapConsumer} = {};

  // Context stack to track Three.js contexts
  const contextStack: ContextInfo[] = [];

  // Helper functions for context management
  const isThreeJsComponent = (elementName: string): boolean => {
    return threeJsComponents.has(elementName);
  };

  const isCurrentlyInThreeJsContext = (): boolean => {
    return (
      contextStack.length > 0 && contextStack[contextStack.length - 1]?.isThreeJsContext === true
    );
  };

  const pushContext = (isThreeJs: boolean): void => {
    contextStack.push({
      depth: contextStack.length,
      isThreeJsContext: isThreeJs,
    });
  };

  const popContext = (): void => {
    contextStack.pop();
  };

  return {
    name: "inject-data-locator-original-source",
    post() {
      // Removed `file` as it's not used here
      // SourceMapConsumer from `source-map-js` does not have a destroy method.
      // Clearing the reference is enough for GC if needed.
      consumerHolder.consumer = undefined;
    },
    pre(file: BabelFile) {
      // `file` is Babel's File object, has `opts` like `filename`
      if (options.inputSourceMap) {
        try {
          let rawMap: RawSourceMap;

          if (typeof options.inputSourceMap === "string") {
            rawMap = JSON.parse(options.inputSourceMap) as RawSourceMap;
          } else {
            rawMap = options.inputSourceMap;
          }
          consumerHolder.consumer = new SourceMapConsumer(rawMap);
        } catch (errCaught: unknown) {
          const error = errCaught as ErrorWithMessage;

          console.warn(
            `[inject-data-locator-original-source] Failed to initialize SourceMapConsumer for ${file.opts.filename}:`,
            error.message,
          );
          consumerHolder.consumer = undefined;
        }
      } else {
        consumerHolder.consumer = undefined;
      }
    },
    visitor: {
      JSXElement: {
        enter(path: NodePath<BabelTypes.JSXElement>, state: BabelState) {
          const openingElement = path.node.openingElement;

          // Determine the element name
          let elementName = "Unknown";

          if (t.isJSXIdentifier(openingElement.name)) {
            elementName = openingElement.name.name;
          } else if (t.isJSXMemberExpression(openingElement.name)) {
            elementName = openingElement.name.property.name;
          }

          // Skip React Fragments
          if (elementName === "Fragment") {
            return;
          }

          // Check if this is a Three.js component
          const isThreeJsComp = isThreeJsComponent(elementName);

          // Push context when entering any element
          // If current element is Three.js OR we're already in Three.js context, mark as Three.js context
          pushContext(isThreeJsComp || isCurrentlyInThreeJsContext());

          // If we're in a Three.js context, skip data-locator injection entirely
          if (isCurrentlyInThreeJsContext()) {
            console.log(
              `[inject-data-locator-original-source] Skipping data-locator injection for ${elementName} (inside Three.js context, ${threeJsComponents.size} components loaded)`,
            );
            return;
          }

          // Continue with normal data-locator injection for non-Three.js elements
          const currentConsumer = consumerHolder.consumer;
          const attributes = openingElement.attributes;
          const filename = state.file.opts.filename || "unknown";

          let filePath = filename;
          const srcIndex = filename.lastIndexOf("/src/");

          if (srcIndex !== -1) {
            filePath = filename.substring(srcIndex + 1);
          } else {
            filePath = filename.split("/").pop() || filename;
          }

          const hasDataLocator = attributes.some(
            (attr: BabelTypes.JSXAttribute | BabelTypes.JSXSpreadAttribute) =>
              t.isJSXAttribute(attr) && attr.name.name === "data-locator",
          );

          if (!hasDataLocator && path.node.loc) {
            const {start} = path.node.loc;
            let finalLine = start.line; // 1-indexed
            let finalColumn = start.column; // 0-indexed
            let mapped = false;

            if (currentConsumer) {
              try {
                const originalPosition = currentConsumer.originalPositionFor({
                  // 1-indexed for lookup
                  column: start.column,
                  line: start.line, // 0-indexed for lookup
                });

                if (
                  originalPosition &&
                  originalPosition.line != null &&
                  originalPosition.column != null
                ) {
                  finalLine = originalPosition.line; // 1-indexed from sourcemap
                  finalColumn = originalPosition.column; // 0-indexed from sourcemap
                  mapped = true;
                }
              } catch (errCaught: unknown) {
                const error = errCaught as ErrorWithMessage;

                console.warn(
                  `[inject-data-locator-original-source] Error during source map lookup for ${elementName} in ${filename}:L${start.line}:C${start.column}`,
                  error.message,
                );
              }
            }

            const locatorValue = `${filePath}:${elementName}:${finalLine}:${finalColumn}`;
            const dataLocatorAttr = t.jsxAttribute(
              t.jsxIdentifier("data-locator"),
              t.stringLiteral(locatorValue),
            );

            openingElement.attributes.push(dataLocatorAttr);

            console.log(
              `[inject-data-locator-original-source] Added data-locator (${mapped ? "original" : "generated"}): ${locatorValue} to ${elementName} in ${filename}${mapped ? ` (gen L${start.line}:C${start.column})` : `(L${start.line}:C${start.column})`}`,
            );
          }
        },
        exit() {
          // Pop context when exiting any JSX element
          popContext();
        },
      },
    },
  };
};

// Babel plugin signature: a function that returns the plugin object.
// Receives babel object (with types, etc.) as first argument, and plugin options as second.
export default function (
  babelAPI: {types: typeof BabelTypes; assertVersion: (version: number) => void},
  options: CustomPluginOptions,
) {
  babelAPI.assertVersion(7); // Ensure compatibility with Babel 7+

  return createPluginLogic(babelAPI, options);
}
