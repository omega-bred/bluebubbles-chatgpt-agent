import React from "react";

import { useKeycloak } from "./auth/useKeycloak";
import { AdminPage } from "./pages/AdminPage";
import { AccountLinkPage } from "./pages/AccountLinkPage";
import { AccountPage } from "./pages/AccountPage";
import { LandingPage } from "./pages/LandingPage";
import { OauthCallbackPage } from "./pages/OauthCallbackPage";

export default function App() {
  const [path, setPath] = React.useState(window.location.pathname);
  const auth = useKeycloak();

  React.useEffect(() => {
    const update = () => setPath(window.location.pathname);
    window.addEventListener("popstate", update);
    return () => window.removeEventListener("popstate", update);
  }, []);

  if (path.startsWith("/oauth/callback")) {
    return <OauthCallbackPage auth={auth} />;
  }

  if (path.startsWith("/account/link")) {
    return <AccountLinkPage auth={auth} />;
  }

  if (path.startsWith("/account")) {
    return <AccountPage auth={auth} />;
  }

  if (path.startsWith("/admin")) {
    return <AdminPage auth={auth} />;
  }

  return <LandingPage auth={auth} />;
}
