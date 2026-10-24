import React from "react";

import { hasAdminRole, initKeycloak, isAuthenticated, subscribeAuth, updateToken } from "./keycloak";

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
    const unsubscribe = subscribeAuth(() => {
      if (active) setState({ ready: true, authenticated: isAuthenticated(), admin: hasAdminRole() });
    });
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
      if (isAuthenticated()) {
        void updateToken(45)
          .then(() => {
            if (active) {
              setState({ ready: true, authenticated: isAuthenticated(), admin: hasAdminRole() });
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
      unsubscribe();
      window.clearInterval(timer);
    };
  }, []);

  return state;
}
