import { login, logout } from "../auth/keycloak";
import { trackEvent } from "../services/analytics";
import type { AuthState } from "../auth/useKeycloak";

export function SiteNav({ auth }: { auth: AuthState }) {
  const trackNav = (target: string) => trackEvent("web_nav_click", { target });

  return (
    <header className="site-nav">
      <a className="brand" href="/" onClick={() => trackNav("home")}>
        <span className="brand-mark">BC</span>
        <span>BlueChat</span>
      </a>
      <nav>
        <a href="/account" onClick={() => trackNav("account")}>
          Account
        </a>
        <a href="/help" onClick={() => trackNav("help")}>
          Help
        </a>
        {auth.ready && auth.admin ? (
          <a href="/admin" onClick={() => trackNav("admin")}>
            Admin
          </a>
        ) : null}
        {auth.ready && auth.authenticated ? (
          <button
            className="link-button"
            onClick={() => {
              trackEvent("web_auth_logout_start", { source: "nav" });
              void logout();
            }}
          >
            Sign out
          </button>
        ) : (
          <button
            className="link-button"
            onClick={() => {
              trackEvent("web_auth_login_start", { source: "nav" });
              void login();
            }}
          >
            Log in
          </button>
        )}
      </nav>
    </header>
  );
}
