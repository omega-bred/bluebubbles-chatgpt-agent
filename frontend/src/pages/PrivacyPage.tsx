import type { AuthState } from "../auth/useKeycloak";
import { SiteNav } from "../components/SiteNav";

const lastUpdated = "May 18, 2026";

export function PrivacyPage({ auth }: { auth: AuthState }) {
  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main narrow terms-main">
        <section className="account-heading">
          <p className="eyebrow">Legal</p>
          <h1>Privacy Policy</h1>
          <p>Last updated {lastUpdated}</p>
        </section>

        <article className="terms-content">
          <section>
            <h2>Overview</h2>
            <p>
              This Privacy Policy explains how BlueChat collects, uses, stores,
              shares, and protects information when you text +1 (415) 867-4956, use the website,
              link accounts, or connect integrations. The service is operated for users in the
              United States and is not directed to children or users under 18.
            </p>
          </section>

          <section>
            <h2>Information We Collect</h2>
            <p>
              We may collect messages, attachments, sender identifiers, chat identifiers, timestamps,
              conversation history, thread context, reactions, generated content, account profile
              details, linked chat identities, login metadata, OAuth tokens, integration metadata,
              calendar data you ask the agent to access, usage limits, feedback, logs,
              device/browser metadata, and technical diagnostics needed to operate the service.
            </p>
          </section>

          <section>
            <h2>How We Use Information</h2>
            <p>
              We use information to receive and respond to messages, run AI models and tools,
              maintain conversation context, personalize responses, link identities to canonical
              accounts, provide account dashboards, operate integrations, enforce usage limits,
              prevent abuse, secure and debug the service, improve reliability, comply with legal
              obligations, and communicate service or account-related information.
            </p>
          </section>

          <section>
            <h2>AI and Tool Providers</h2>
            <p>
              Message content, prompts, files, context, and tool results may be sent to configured
              AI model providers and infrastructure providers so the service can generate responses
              or perform requested actions. Connected services such as Google Calendar,
              Keycloak, message relay services, Giphy, memory services, hosting providers, databases, logging
              systems, and deployment infrastructure may process information according to their own
              terms and privacy practices.
            </p>
          </section>

          <section>
            <h2>Account Linking and OAuth</h2>
            <p>
              If you link a website account or OAuth integration, we store metadata and credentials
              needed to keep that connection working. OAuth tokens are used only for the connected
              account and actions you authorize or request. You may be able to unlink supported
              integrations from the account dashboard.
            </p>
          </section>

          <section>
            <h2>Legal Bases and Consent</h2>
            <p>
              Where privacy law requires a legal basis, we process information to provide the
              service you request, with your consent where applicable, to protect the service and
              other users, to comply with legal obligations, and for legitimate operational interests
              such as security, debugging, abuse prevention, and service improvement.
            </p>
          </section>

          <section>
            <h2>Sharing</h2>
            <p>
              We do not sell personal information. We may share information with service providers,
              infrastructure providers, AI model providers, connected integrations, legal or security
              advisors, authorities when required by law, and other parties if needed to investigate
              abuse, protect rights and safety, enforce terms, complete a business transfer, or
              operate features you request.
            </p>
          </section>

          <section>
            <h2>Retention</h2>
            <p>
              We keep information for as long as needed to operate the service, preserve account and
              integration state, maintain security, debug issues, enforce limits, comply with legal
              obligations, and resolve disputes. Some information may remain in backups, logs,
              generated artifacts, or third-party systems for a limited period after deletion from
              active systems.
            </p>
          </section>

          <section>
            <h2>Deletion and Access Requests</h2>
            <p>
              You may request access to, correction of, deletion of, or export of personal
              information where required by applicable law. We may need to verify your identity and
              may retain information when needed for security, legal compliance, fraud prevention,
              dispute resolution, or service operations. Some requests may need to be made directly
              to third-party providers for data they control.
            </p>
          </section>

          <section>
            <h2>California Privacy</h2>
            <p>
              California residents may have rights to know, access, correct, delete, and limit
              certain uses of personal information, and to opt out of sale or sharing where
              applicable. We do not sell personal information. We also do not knowingly share
              personal information for cross-context behavioral advertising.
            </p>
          </section>

          <section>
            <h2>EEA, UK, and International Users</h2>
            <p>
              The service is intended for U.S. users and is not directed to users in the European
              Economic Area or United Kingdom. If you access it from outside the United States, your
              information may be transferred to and processed in the United States and other
              countries that may not provide the same level of data protection as your jurisdiction.
              Depending on applicable law, you may have rights to access, correct, erase, restrict,
              object to, or port your personal data, and to lodge a complaint with a supervisory
              authority.
            </p>
          </section>

          <section>
            <h2>Children</h2>
            <p>
              The service is not for anyone under 18. We do not knowingly collect personal
              information from children. If you believe a child has provided information to the
              service, contact the operator so the information can be reviewed and deleted where
              appropriate.
            </p>
          </section>

          <section>
            <h2>Security</h2>
            <p>
              We use reasonable administrative, technical, and operational safeguards designed to
              protect information. No system can be guaranteed secure, and you are responsible for
              protecting your devices, accounts, credentials, linked services, and message history.
            </p>
          </section>

          <section>
            <h2>Your Choices</h2>
            <p>
              You can stop using the service by no longer texting the agent. You can unlink
              supported integrations through the account dashboard where available. You should avoid
              sending sensitive information unless it is necessary for your request and you are
              comfortable with the processing described here.
            </p>
          </section>

          <section>
            <h2>Changes</h2>
            <p>
              We may update this Privacy Policy from time to time. Continued use of the service
              after an update means the updated policy applies to future use.
            </p>
          </section>
        </article>
      </main>
    </div>
  );
}
