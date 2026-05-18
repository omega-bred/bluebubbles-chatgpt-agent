import React from "react";

import { hasAdminRole, initKeycloak, keycloak } from "./keycloak";

export type AuthState = {
  ready: boolean;
  authenticated: boolean;
  admin: boolean;
};

export function useKeycloak(): AuthState {
  const [state, setState] = React.useState<AuthState>({
    ready: false,
    authenticated: false,
    admin: false,
  });

  React.useEffect(() => {
    let active = true;
    initKeycloak()
      .then((authenticated) => {
        if (active) {
          setState({ ready: true, authenticated, admin: authenticated && hasAdminRole() });
        }
      })
      .catch(() => {
        if (active) {
          setState({ ready: true, authenticated: false, admin: false });
        }
      });
    const timer = window.setInterval(() => {
      if (keycloak.authenticated) {
        void keycloak
          .updateToken(45)
          .then(() => {
            if (active) {
              setState({ ready: true, authenticated: true, admin: hasAdminRole() });
            }
          })
          .catch(() => {
            if (active) {
              setState({ ready: true, authenticated: false, admin: false });
            }
          });
      }
    }, 30_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  return state;
}
