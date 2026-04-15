import { login, register } from "../auth/keycloak";
import { SiteNav } from "./SiteNav";

export function AuthGate({ title }: { title: string }) {
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
