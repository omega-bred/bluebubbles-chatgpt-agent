import type { AuthState } from "../auth/useKeycloak";
import { SiteNav } from "../components/SiteNav";

type ServiceKey = "coder" | "gcal";
type ResultStatus = "error" | "success";

const serviceLabels: Record<ServiceKey, string> = {
  coder: "Coder",
  gcal: "Google Calendar",
};

const defaultMessages: Record<ServiceKey, Record<ResultStatus, string>> = {
  coder: {
    error: "Coder could not be linked.",
    success: "Coder linked. You can close this tab.",
  },
  gcal: {
    error: "Google Calendar could not be linked.",
    success: "Google Calendar linked. You can close this tab.",
  },
};

export function OauthCallbackPage({ auth }: { auth: AuthState }) {
  const params = new URLSearchParams(window.location.search);
  const service = parseService(params.get("service"));
  const status = parseStatus(params.get("status"));
  const label = serviceLabels[service];
  const message = params.get("message") || defaultMessages[service][status];

  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main narrow">
        <section className={`oauth-result ${status === "success" ? "success" : "error"}`}>
          <p className="eyebrow">{label} OAuth</p>
          <h1>{message}</h1>
          <p>
            {status === "success"
              ? "The linking flow is complete."
              : "Return to iMessage and start the linking flow again."}
          </p>
          <div className="hero-actions">
            <a className="button button-primary" href="/account">
              View account
            </a>
            <a className="button button-secondary" href="/">
              Home
            </a>
          </div>
        </section>
      </main>
    </div>
  );
}

function parseService(value: string | null): ServiceKey {
  return value === "coder" || value === "gcal" ? value : "gcal";
}

function parseStatus(value: string | null): ResultStatus {
  return value === "success" ? "success" : "error";
}
