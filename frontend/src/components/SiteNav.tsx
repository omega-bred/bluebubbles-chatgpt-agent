import { login, logout } from "../auth/keycloak";
import type { AuthState } from "../auth/useKeycloak";

export function SiteNav({ auth }: { auth: AuthState }) {
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
