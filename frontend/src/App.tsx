import React from "react";

import { useKeycloak } from "./auth/useKeycloak";
import { AccountLinkPage } from "./pages/AccountLinkPage";
import { AccountPage } from "./pages/AccountPage";
import { LandingPage } from "./pages/LandingPage";

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
