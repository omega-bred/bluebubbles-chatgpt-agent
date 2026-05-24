import React from "react";
import "cap-widget";

import type { AuthState } from "../auth/useKeycloak";
import { SiteNav } from "../components/SiteNav";
import { contactApi } from "../services/api-client";
import { trackEvent } from "../services/analytics";

type ContactStatus = "idle" | "loading" | "submitting" | "sent" | "failed";

declare global {
  namespace JSX {
    interface IntrinsicElements {
      "cap-widget": React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement> & {
        "data-cap-api-endpoint"?: string;
        required?: boolean;
      };
    }
  }
}

export function HelpContactPage({ auth }: { auth: AuthState }) {
  const [status, setStatus] = React.useState<ContactStatus>("loading");
  const [error, setError] = React.useState<string | null>(null);
  const [contactEnabled, setContactEnabled] = React.useState(true);
  const [capEndpoint, setCapEndpoint] = React.useState<string | null>(null);
  const [captchaRequired, setCaptchaRequired] = React.useState(true);
  const [captchaConfigured, setCaptchaConfigured] = React.useState(false);
  const [capToken, setCapToken] = React.useState("");
  const [form, setForm] = React.useState({
    name: "",
    email: "",
    subject: "",
    message: "",
  });
  const capRef = React.useRef<HTMLElement | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    setStatus("loading");
    contactApi
      .getConfig()
      .then((config) => {
        if (cancelled) {
          return;
        }
        setContactEnabled(Boolean(config.enabled));
        setCapEndpoint(config.cap_api_endpoint || null);
        setCaptchaRequired(Boolean(config.captcha_required));
        setCaptchaConfigured(Boolean(config.captcha_configured));
        setStatus("idle");
        trackEvent("web_contact_config_loaded", {
          enabled: Boolean(config.enabled),
          captcha_configured: Boolean(config.captcha_configured),
        });
      })
      .catch((err) => {
        if (cancelled) {
          return;
        }
        setStatus("failed");
        setError(err instanceof Error ? err.message : "Unable to load the contact form.");
        trackEvent("web_contact_config_failed");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  React.useEffect(() => {
    const widget = capRef.current;
    if (!widget) {
      return;
    }
    const handleSolve = (event: Event) => {
      const token = (event as CustomEvent<{ token?: string }>).detail?.token || "";
      setCapToken(token);
      trackEvent("web_contact_captcha_solved");
    };
    const handleError = () => {
      setCapToken("");
      trackEvent("web_contact_captcha_failed");
    };
    const handleReset = () => setCapToken("");
    widget.addEventListener("solve", handleSolve);
    widget.addEventListener("error", handleError);
    widget.addEventListener("reset", handleReset);
    return () => {
      widget.removeEventListener("solve", handleSolve);
      widget.removeEventListener("error", handleError);
      widget.removeEventListener("reset", handleReset);
    };
  }, [capEndpoint]);

  const submitDisabled =
    status === "loading" ||
    status === "submitting" ||
    !contactEnabled ||
    (captchaRequired && (!captchaConfigured || !capToken));

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setStatus("submitting");
    try {
      const response = await contactApi.createMessage(
        {
          ...form,
          cap_token: captchaRequired ? capToken : capToken || "not-required",
        },
        auth.authenticated,
      );
      setStatus("sent");
      setForm({ name: "", email: "", subject: "", message: "" });
      setCapToken("");
      trackEvent("web_contact_submit", { status: "success" });
      setError(`Message received. Reference ${response.message_id}.`);
    } catch (err) {
      setStatus("failed");
      setError(err instanceof Error ? err.message : "Unable to send your message.");
      trackEvent("web_contact_submit", { status: "failed" });
    }
  };

  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main help-main">
        <section className="account-heading">
          <p className="eyebrow">Help</p>
          <h1>Contact support</h1>
          <p>
            Send a note about account access, billing, integrations, or anything that looks off in
            the agent.
          </p>
        </section>

        <section className="help-grid">
          <article className="help-panel">
            <h2>What helps</h2>
            <p>
              Include the phone number, BlueChat email, LXMF address, or website account email you
              use with the agent. Do not send passwords, API keys, or private tokens.
            </p>
          </article>

          <form className="contact-form" onSubmit={(event) => void submit(event)}>
            <label>
              <span>Name</span>
              <input
                autoComplete="name"
                maxLength={200}
                required
                value={form.name}
                onChange={(event) => setForm({ ...form, name: event.target.value })}
              />
            </label>
            <label>
              <span>Email</span>
              <input
                autoComplete="email"
                maxLength={320}
                required
                type="email"
                value={form.email}
                onChange={(event) => setForm({ ...form, email: event.target.value })}
              />
            </label>
            <label>
              <span>Subject</span>
              <input
                maxLength={200}
                required
                value={form.subject}
                onChange={(event) => setForm({ ...form, subject: event.target.value })}
              />
            </label>
            <label>
              <span>Message</span>
              <textarea
                maxLength={5000}
                required
                rows={8}
                value={form.message}
                onChange={(event) => setForm({ ...form, message: event.target.value })}
              />
            </label>

            {captchaRequired ? (
              captchaConfigured && capEndpoint ? (
                <cap-widget
                  ref={capRef}
                  required
                  data-cap-api-endpoint={capEndpoint}
                  data-cap-i18n-initial-state="Verify you're human"
                  data-cap-i18n-solved-label="You're human"
                />
              ) : (
                <p className="error-banner">Contact verification is not configured yet.</p>
              )
            ) : null}

            {!contactEnabled ? (
              <p className="error-banner">The contact form is currently disabled.</p>
            ) : null}

            {error ? (
              <p className={status === "sent" ? "success-banner" : "error-banner"}>{error}</p>
            ) : null}

            <button className="button button-primary" disabled={submitDisabled} type="submit">
              {status === "submitting" ? "Sending" : "Send message"}
            </button>
          </form>
        </section>
      </main>
    </div>
  );
}
