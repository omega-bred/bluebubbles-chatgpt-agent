import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { websiteAccountApi } from "../services/api-client";
import { trackEvent } from "../services/analytics";

export function AccountLinkPage({ auth }: { auth: AuthState }) {
  const token = new URLSearchParams(window.location.search).get("token") || "";
  const [status, setStatus] = React.useState("Ready to connect this BlueChat sender.");
  const [done, setDone] = React.useState(false);

  React.useEffect(() => {
    if (!auth.authenticated || !token || done) {
      return;
    }
    setStatus("Connecting this BlueChat sender to your account.");
    websiteAccountApi
      .redeemLink(token)
      .then((response) => {
        setStatus(
          response.status === "already_linked"
            ? "This BlueChat sender is already linked to your account."
            : "This BlueChat sender is linked to your account.",
        );
        trackEvent("web_account_link_redeemed", { status: response.status || "linked" });
        setDone(true);
      })
      .catch((err) => {
        const statusCode = err?.response?.status;
        setStatus(err?.response?.data?.message || "This link could not be redeemed.");
        trackEvent("web_account_link_failed", { status_code: statusCode || 0 });
      });
  }, [auth.authenticated, done, token]);

  if (!auth.ready) {
    return <CenteredMessage title="Loading account" body="Getting your session ready." />;
  }

  if (!auth.authenticated) {
    return <AuthGate title="Connect BlueChat" />;
  }

  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main narrow">
        <section className="account-heading">
          <p className="eyebrow">Connect BlueChat</p>
          <h1>{status}</h1>
          <p>Your website account can now show the services linked to this sender.</p>
          <a
            className="button button-primary"
            href="/account"
            onClick={() => trackEvent("web_account_link_account_click")}
          >
            View account
          </a>
        </section>
      </main>
    </div>
  );
}
