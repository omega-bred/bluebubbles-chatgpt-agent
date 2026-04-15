import type { AuthState } from "../auth/useKeycloak";
import { SiteNav } from "../components/SiteNav";

const screenOne = new URL("../../../images/screen1.png", import.meta.url).href;
const screenTwo = new URL("../../../images/screen2.png", import.meta.url).href;
const screenThree = new URL("../../../images/screen3.jpg", import.meta.url).href;

export function LandingPage({ auth }: { auth: AuthState }) {
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
          <h1>ChatGPT is in your iMessage.</h1>
          <p className="hero-lede">
            Talk to ChatGPT, OpenAI, Gemini, Claude, or any other configured model from iMessage.
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
            Ask for plans, recipes, reminders, coding help, images, GIFs, or calendar changes from
            the same thread your people are already using.
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
          <p>
            Bring the model into the room only when it is useful, with replies and reactions that
            fit iMessage.
          </p>
        </article>
        <article>
          <h3>Linked accounts</h3>
          <p>
            Connect your sender to the web account and see services like Coder and Google Calendar
            in one place.
          </p>
        </article>
      </section>
    </div>
  );
}
