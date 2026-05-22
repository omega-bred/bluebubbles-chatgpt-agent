import React from "react";

import { currentUserSubject } from "./auth/keycloak";
import { useKeycloak } from "./auth/useKeycloak";
import { AdminPage } from "./pages/AdminPage";
import { AdminFeedbackPage } from "./pages/AdminFeedbackPage";
import { AccountLinkPage } from "./pages/AccountLinkPage";
import { AccountPage } from "./pages/AccountPage";
import { LandingPage } from "./pages/LandingPage";
import { OauthCallbackPage } from "./pages/OauthCallbackPage";
import { PrivacyPage } from "./pages/PrivacyPage";
import { TermsPage } from "./pages/TermsPage";
import { identifyAuthenticatedSession, trackPageView } from "./services/analytics";

export default function App() {
  const [path, setPath] = React.useState(window.location.pathname);
  const auth = useKeycloak();

  React.useEffect(() => {
    const update = () => setPath(window.location.pathname);
    window.addEventListener("popstate", update);
    return () => window.removeEventListener("popstate", update);
  }, []);

  React.useEffect(() => {
    if (!auth.ready) {
      return;
    }
    trackPageView(path, { authenticated: auth.authenticated, admin: auth.admin });
  }, [auth.admin, auth.authenticated, auth.ready, path]);

  React.useEffect(() => {
    if (!auth.ready || !auth.authenticated) {
      return;
    }
    void identifyAuthenticatedSession(currentUserSubject(), {
      authenticated: true,
      admin: auth.admin,
    });
  }, [auth.admin, auth.authenticated, auth.ready]);

  if (path.startsWith("/oauth/callback")) {
    return <OauthCallbackPage auth={auth} />;
  }

  if (path.startsWith("/account/link")) {
    return <AccountLinkPage auth={auth} />;
  }

  if (path.startsWith("/account")) {
    return <AccountPage auth={auth} />;
  }

  if (path.startsWith("/admin/feedback")) {
    return <AdminFeedbackPage auth={auth} />;
  }

  if (path.startsWith("/admin")) {
    return <AdminPage auth={auth} />;
  }

  if (path.startsWith("/terms")) {
    return <TermsPage auth={auth} />;
  }

  if (path.startsWith("/privacy")) {
    return <PrivacyPage auth={auth} />;
  }

  return <LandingPage auth={auth} />;
}
