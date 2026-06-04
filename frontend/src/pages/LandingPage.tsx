import { useEffect, useState } from "react";

import type { TextingNumberResponse } from "../client";
import type { AuthState } from "../auth/useKeycloak";
import { SiteNav } from "../components/SiteNav";
import { trackEvent } from "../services/analytics";
import { textingApi } from "../services/api-client";

const screenOne = new URL("../../../images/screen1.png", import.meta.url).href;
const screenTwo = new URL("../../../images/screen2.png", import.meta.url).href;
const screenThree = new URL("../../../images/screen3.jpg", import.meta.url).href;
const fallbackTextingNumber: TextingNumberResponse = {
  phone_number_e164: "+14158674956",
  display_number: "+1 (415) 867-4956",
  default_message: "Hi BlueChatAI, let's start.",
  sms_url: "sms:+14158674956",
};
const standardMonthlyMessageLimit = "200";
const premiumMonthlyMessageLimit = "5,000";

export function LandingPage({ auth }: { auth: AuthState }) {
  const [textingNumber, setTextingNumber] =
    useState<TextingNumberResponse>(fallbackTextingNumber);

  useEffect(() => {
    let cancelled = false;
    textingApi
      .getNumber()
      .then((number) => {
        if (!cancelled) {
          setTextingNumber(number);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setTextingNumber(fallbackTextingNumber);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="site-shell">
      <SiteNav auth={auth} />
      <section
        className="hero"
        style={{
          backgroundImage: `linear-gradient(90deg, rgba(10,10,10,.88), rgba(10,10,10,.54), rgba(10,10,10,.18)), url(${screenThree})`,
        }}
      >
        <div className="hero-copy">
          <p className="eyebrow">AI where the conversation already is</p>
          <h1>AI is in your messages.</h1>
          <p className="hero-lede">
            Text {textingNumber.display_number} to try BlueChat with ChatGPT, Gemini, Claude,
            or any other configured model.
          </p>
          <div className="hero-actions">
            <a
              className="button button-primary button-cta"
              href={textingNumber.sms_url}
              onClick={() => trackEvent("web_landing_sms_click", { source: "hero" })}
            >
              Text {textingNumber.display_number}
            </a>
            <a
              className="button button-secondary"
              href="#how-it-works"
              onClick={() => trackEvent("web_landing_how_click", { source: "hero" })}
            >
              See how it works
            </a>
          </div>
          <p className="hero-cta-note">Start a conversation from Messages. No app install needed.</p>
        </div>
      </section>

      <section className="section intro-band" id="how-it-works">
        <div className="section-copy">
          <p className="eyebrow">Text naturally</p>
          <h2>No app to open. No dashboard to babysit.</h2>
          <p>
            Text {textingNumber.display_number} and ask for plans, recipes, reminders, coding help,
            images, GIFs, or calendar changes from the same thread your people are already using.
          </p>
        </div>
        <div className="image-strip">
          <img src={screenOne} alt="One-on-one message chat with ChatGPT or any AI Model" />
          <img src={screenTwo} alt="message chat asking ChatGPT for a party schedule" />
        </div>
      </section>

      <section className="section capability-grid">
        <article>
          <h3>Personal chats</h3>
          <p>Fast answers, memory, calendar help, coding support, and model choice from one thread.</p>
        </article>
        <article>
          <h3>Group chats</h3>
          <p>
            Bring the model into the room only when it is useful, with replies and reactions that
            fit BlueChat.
          </p>
        </article>
        <article>
          <h3>Linked accounts</h3>
          <p>
            Connect your sender to the web account and see services like Google Calendar in one
            place.
          </p>
        </article>
        <article>
          <h3>Clear limits</h3>
          <p>
            Free accounts include {standardMonthlyMessageLimit} messages per month. Premium includes{" "}
            {premiumMonthlyMessageLimit} messages per month.
          </p>
        </article>
      </section>
    </div>
  );
}
