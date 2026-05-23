import { trackEvent } from "../services/analytics";

export function SiteFooter() {
  const trackFooter = (target: string) => trackEvent("web_footer_click", { target });

  return (
    <footer className="site-footer">
      <a className="brand footer-brand" href="/" onClick={() => trackFooter("home")}>
        <span className="brand-mark">BC</span>
        <span>BlueChat</span>
      </a>
      <nav aria-label="Footer">
        <a href="/help" onClick={() => trackFooter("help")}>
          Help
        </a>
        <a href="/account" onClick={() => trackFooter("account")}>
          Account
        </a>
        <a href="/terms" onClick={() => trackFooter("terms")}>
          Terms
        </a>
        <a href="/privacy" onClick={() => trackFooter("privacy")}>
          Privacy
        </a>
      </nav>
    </footer>
  );
}
