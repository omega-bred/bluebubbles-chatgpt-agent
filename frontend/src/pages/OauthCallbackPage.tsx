import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import { SiteNav } from "../components/SiteNav";
import { trackEvent } from "../services/analytics";

type ResultStatus = "error" | "success";

const serviceLabel = "Google Calendar";
const defaultMessages: Record<ResultStatus, string> = {
  error: "Google Calendar could not be linked.",
  success: "Google Calendar linked. You can close this tab.",
};

export function OauthCallbackPage({ auth }: { auth: AuthState }) {
  const params = new URLSearchParams(window.location.search);
  const service = "gcal";
  const status = parseStatus(params.get("status"));
  const message = params.get("message") || defaultMessages[status];

  React.useEffect(() => {
    trackEvent("web_oauth_callback_view", { service, status });
  }, [service, status]);

  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main narrow">
        <section className={`oauth-result ${status === "success" ? "success" : "error"}`}>
          <p className="eyebrow">{serviceLabel} OAuth</p>
          <h1>{message}</h1>
          <p>
            {status === "success"
              ? "The linking flow is complete."
              : "Return to BlueChat and start the linking flow again."}
          </p>
          <div className="hero-actions">
            <a
              className="button button-primary"
              href="/account"
              onClick={() => trackEvent("web_oauth_account_click", { service, status })}
            >
              View account
            </a>
            <a
              className="button button-secondary"
              href="/"
              onClick={() => trackEvent("web_oauth_home_click", { service, status })}
            >
              Home
            </a>
          </div>
        </section>
      </main>
    </div>
  );
}

function parseStatus(value: string | null): ResultStatus {
  return value === "success" ? "success" : "error";
}
