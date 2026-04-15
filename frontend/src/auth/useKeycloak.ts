import React from "react";

import { initKeycloak, keycloak } from "./keycloak";

export type AuthState = {
  ready: boolean;
  authenticated: boolean;
};

export function useKeycloak(): AuthState {
  const [state, setState] = React.useState<AuthState>({
    ready: false,
    authenticated: false,
  });

  React.useEffect(() => {
    let active = true;
    initKeycloak()
      .then((authenticated) => {
        if (active) {
          setState({ ready: true, authenticated });
        }
      })
      .catch(() => {
        if (active) {
          setState({ ready: true, authenticated: false });
        }
      });
    const timer = window.setInterval(() => {
      if (keycloak.authenticated) {
        void keycloak.updateToken(45);
      }
    }, 30_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  return state;
}
