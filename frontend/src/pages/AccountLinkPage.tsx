import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { websiteAccountApi } from "../services/api-client";

export function AccountLinkPage({ auth }: { auth: AuthState }) {
  const token = new URLSearchParams(window.location.search).get("token") || "";
  const [status, setStatus] = React.useState("Ready to connect this iMessage sender.");
  const [done, setDone] = React.useState(false);

  React.useEffect(() => {
    if (!auth.authenticated || !token || done) {
      return;
    }
    setStatus("Connecting this iMessage sender to your account.");
    websiteAccountApi
      .redeemLink(token)
      .then((response) => {
        setStatus(
          response.status === "already_linked"
            ? "This iMessage sender is already linked to your account."
            : "This iMessage sender is linked to your account.",
        );
        setDone(true);
      })
      .catch((err) => {
        setStatus(err?.response?.data?.message || "This link could not be redeemed.");
      });
  }, [auth.authenticated, done, token]);

  if (!auth.ready) {
    return <CenteredMessage title="Loading account" body="Getting your session ready." />;
  }

  if (!auth.authenticated) {
    return <AuthGate title="Connect iMessage" />;
  }

  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main narrow">
        <section className="account-heading">
          <p className="eyebrow">Connect iMessage</p>
          <h1>{status}</h1>
          <p>Your website account can now show the services linked to this sender.</p>
          <a className="button button-primary" href="/account">
            View account
          </a>
        </section>
      </main>
    </div>
  );
}
