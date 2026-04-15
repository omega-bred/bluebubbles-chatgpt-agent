import React from "react";

import {
  initKeycloak,
  keycloak,
  login,
  logout,
  register,
} from "./auth/keycloak";
import type {
  WebsiteIntegrationSummary,
  WebsiteLinkedAccountsResponse,
} from "./client";
import { websiteAccountApi } from "./services/api-client";

const screenOne = new URL("../../images/screen1.png", import.meta.url).href;
const screenTwo = new URL("../../images/screen2.png", import.meta.url).href;
const screenThree = new URL("../../images/screen3.jpg", import.meta.url).href;

type AuthState = {
  ready: boolean;
  authenticated: boolean;
};

function useKeycloak() {
  const [state, setState] = React.useState<AuthState>({
    ready: false,
    authenticated: false,
  });

  React.useEffect(() => {
    let active = true;
    initKeycloak()
      .then((authenticated) => {
        if (active) {
          setState({ ready: true, authenticated });
        }
      })
      .catch(() => {
        if (active) {
          setState({ ready: true, authenticated: false });
        }
      });
    const timer = window.setInterval(() => {
      if (keycloak.authenticated) {
        void keycloak.updateToken(45);
      }
    }, 30_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  return state;
}

export default function App() {
  const [path, setPath] = React.useState(window.location.pathname);
  const auth = useKeycloak();

  React.useEffect(() => {
    const update = () => setPath(window.location.pathname);
    window.addEventListener("popstate", update);
    return () => window.removeEventListener("popstate", update);
  }, []);

  if (path.startsWith("/account/link")) {
    return <AccountLinkPage auth={auth} />;
  }

  if (path.startsWith("/account")) {
    return <AccountPage auth={auth} />;
  }

  return <LandingPage auth={auth} />;
}

function LandingPage({ auth }: { auth: AuthState }) {
  return (
    <div className="site-shell">
      <SiteNav auth={auth} />
      <section
        className="hero"
        style={{ backgroundImage: `linear-gradient(90deg, rgba(10,10,10,.88), rgba(10,10,10,.54), rgba(10,10,10,.18)), url(${screenThree})` }}
      >
        <div className="hero-copy">
          <p className="eyebrow">AI where the conversation already is</p>
          <h1>ChatGPT is in your iMessage.</h1>
          <p className="hero-lede">
            Talk to ChatGPT, OpenAI, Gemini, Claude, or any other configured
            model from iMessage.
          </p>
          <div className="hero-actions">
            <a className="button button-primary" href="/account">
              Manage account
            </a>
            <a className="button button-secondary" href="#how-it-works">
              See how it works
            </a>
          </div>
        </div>
      </section>

      <section className="section intro-band" id="how-it-works">
        <div className="section-copy">
          <p className="eyebrow">Text naturally</p>
          <h2>No app to open. No dashboard to babysit.</h2>
          <p>
            Ask for plans, recipes, reminders, coding help, images, GIFs, or
            calendar changes from the same thread your people are already using.
          </p>
        </div>
        <div className="image-strip">
          <img src={screenOne} alt="One-on-one iMessage chat with ChatGPT" />
          <img src={screenTwo} alt="iMessage chat asking ChatGPT for a party schedule" />
        </div>
      </section>

      <section className="section capability-grid">
        <article>
          <h3>Personal chats</h3>
          <p>Fast answers, memory, calendar help, Coder tasks, and model choice from one thread.</p>
        </article>
        <article>
          <h3>Group chats</h3>
          <p>Bring the model into the room only when it is useful, with replies and reactions that fit iMessage.</p>
        </article>
        <article>
          <h3>Linked accounts</h3>
          <p>Connect your sender to the web account and see services like Coder and Google Calendar in one place.</p>
        </article>
      </section>
    </div>
  );
}

function SiteNav({ auth }: { auth: AuthState }) {
  return (
    <header className="site-nav">
      <a className="brand" href="/">
        <span className="brand-mark">CI</span>
        <span>Chat iMessage</span>
      </a>
      <nav>
        <a href="/account">Account</a>
        {auth.ready && auth.authenticated ? (
          <button className="link-button" onClick={() => void logout()}>
            Sign out
          </button>
        ) : (
          <button className="link-button" onClick={() => void login()}>
            Log in
          </button>
        )}
      </nav>
    </header>
  );
}

function AccountPage({ auth }: { auth: AuthState }) {
  const [data, setData] = React.useState<WebsiteLinkedAccountsResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);

  const load = React.useCallback(async () => {
    if (!auth.authenticated) {
      return;
    }
    setLoading(true);
    try {
      setData(await websiteAccountApi.listLinkedAccounts());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load linked accounts.");
    } finally {
      setLoading(false);
    }
  }, [auth.authenticated]);

  React.useEffect(() => {
    void load();
  }, [load]);

  if (!auth.ready) {
    return <CenteredMessage title="Loading account" body="Getting your session ready." />;
  }

  if (!auth.authenticated) {
    return <AuthGate title="Manage your account" />;
  }

  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main">
        <section className="account-heading">
          <p className="eyebrow">Account</p>
          <h1>{data?.account?.display_name || data?.account?.preferred_username || "Your account"}</h1>
          <p>{data?.account?.email || "Signed in with Keycloak."}</p>
        </section>

        {error ? <p className="error-banner">{error}</p> : null}
        {loading ? <p className="muted">Loading linked accounts.</p> : null}

        <section className="linked-list">
          {(data?.integrations || []).length === 0 ? (
            <EmptyLinks />
          ) : (
            data?.integrations?.map((integration) => (
              <LinkedIdentity
                key={integration.link?.link_id}
                integration={integration}
                onDeleted={load}
              />
            ))
          )}
        </section>
      </main>
    </div>
  );
}

function AccountLinkPage({ auth }: { auth: AuthState }) {
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

function LinkedIdentity({
  integration,
  onDeleted,
}: {
  integration: WebsiteIntegrationSummary;
  onDeleted: () => Promise<void>;
}) {
  const [deleting, setDeleting] = React.useState(false);
  const link = integration.link;
  if (!link) {
    return null;
  }
  const calendars = integration.gcal_accounts || [];
  const modelAccess = integration.model_access;
  const modelLabel = modelAccess ? displayModelLabel(modelAccess.current_model_label) : "";
  const planLabel = modelAccess?.is_premium ? "Premium" : "Free";
  const accessNote =
    modelAccess && modelAccess.is_premium ? `${planLabel} account · ${modelLabel}` : `${planLabel} account`;
  return (
    <article className="linked-item">
      <div>
        <p className="eyebrow">iMessage sender</p>
        <h2>{link.is_group ? "Group iMessage chat" : "Direct iMessage sender"}</h2>
        <p className="muted">{link.service || "iMessage"} linked to this account.</p>
        {modelAccess ? (
          <p className="model-note">
            {accessNote}
            {modelAccess.model_selection_configurable ? "" : " · read only"}
          </p>
        ) : null}
      </div>
      <div className="integration-pills">
        {modelAccess ? (
          <span className={modelAccess.is_premium ? "pill premium" : "pill"}>
            {modelLabel === "Free" ? "Free" : `Model ${modelLabel}`}
          </span>
        ) : null}
        <span className={integration.coder_linked ? "pill good" : "pill"}>
          Coder {integration.coder_linked ? "linked" : "not linked"}
        </span>
        <span className={calendars.length > 0 ? "pill good" : "pill"}>
          Google Calendar {calendars.length > 0 ? calendars.map((item) => item.account_id).join(", ") : "not linked"}
        </span>
      </div>
      <button
        className="button button-secondary"
        disabled={deleting}
        onClick={async () => {
          setDeleting(true);
          await websiteAccountApi.deleteLink(link.link_id);
          await onDeleted();
          setDeleting(false);
        }}
      >
        {deleting ? "Unlinking" : "Unlink sender"}
      </button>
    </article>
  );
}

function displayModelLabel(label?: string | null) {
  if (!label) {
    return "Free";
  }
  const normalized = label.trim().toLowerCase();
  if (normalized === "local" || normalized === "model local") {
    return "Free";
  }
  return label;
}

function EmptyLinks() {
  return (
    <article className="empty-state">
      <h2>No iMessage senders linked yet.</h2>
      <p>
        Text the bot and ask it to link your website account. It will send a
        short-lived login link back in iMessage.
      </p>
    </article>
  );
}

function AuthGate({ title }: { title: string }) {
  return (
    <div className="account-shell">
      <SiteNav auth={{ ready: true, authenticated: false }} />
      <main className="account-main narrow">
        <section className="account-heading">
          <p className="eyebrow">Keycloak login</p>
          <h1>{title}</h1>
          <p>Log in or create an account to manage linked iMessage senders.</p>
          <div className="hero-actions">
            <button className="button button-primary" onClick={() => void login()}>
              Log in
            </button>
            <button className="button button-secondary" onClick={() => void register()}>
              Sign up
            </button>
          </div>
        </section>
      </main>
    </div>
  );
}

function CenteredMessage({ title, body }: { title: string; body: string }) {
  return (
    <div className="centered-message">
      <h1>{title}</h1>
      <p>{body}</p>
    </div>
  );
}
