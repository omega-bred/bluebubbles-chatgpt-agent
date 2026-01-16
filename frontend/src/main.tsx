import {HeroUIProvider, ToastProvider} from "@heroui/react";
import React from "react";
import ReactDOM from "react-dom/client";

import App from "./App.tsx";

import "./index.css";
import { ClerkProvider } from "@clerk/clerk-react";

const host = window.location.hostname;
const publishableKey =
  host === 'friendframe.bre.land' || host === 'paper-server.bre.land'
    ? 'pk_live_Y2xlcmsuZnJpZW5kZnJhbWUuYnJlLmxhbmQk'
    : 'pk_test_Y2hhcm1lZC1vcG9zc3VtLTY0LmNsZXJrLmFjY291bnRzLmRldiQ';


ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ClerkProvider publishableKey={publishableKey}>
      <HeroUIProvider>
        <ToastProvider />
        <main className="text-foreground bg-background">
          <App />
        </main>
      </HeroUIProvider>
    </ClerkProvider>
  </React.StrictMode>,
);

