import type { AuthState } from "../auth/useKeycloak";
import { SiteNav } from "../components/SiteNav";

const lastUpdated = "May 18, 2026";

export function TermsPage({ auth }: { auth: AuthState }) {
  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main narrow terms-main">
        <section className="account-heading">
          <p className="eyebrow">Legal</p>
          <h1>Terms of Use</h1>
          <p>Last updated {lastUpdated}</p>
        </section>

        <article className="terms-content">
          <section>
            <h2>Acceptance</h2>
            <p>
              By texting the agent at +1 (415) 867-4956, using the web dashboard, linking an
              account, or using any connected integration, you agree to these Terms of Use. If you
              do not agree, do not use the service.
            </p>
          </section>

          <section>
            <h2>Eligibility</h2>
            <p>
              You must be at least 18 years old to use the service. Do not use the service on
              behalf of another person unless you have permission and legal authority to do so.
            </p>
          </section>

          <section>
            <h2>Acceptable Use</h2>
            <p>
              Do not use the service to send spam, unsolicited messages, harassment, threats, scams,
              malware, abusive content, illegal content, or content that violates another person's
              rights. Do not attempt to bypass rate limits, probe or attack the service, scrape it,
              resell access, impersonate others, or use the service to automate deceptive or harmful
              behavior.
            </p>
          </section>

          <section>
            <h2>Messaging</h2>
            <p>
              You are responsible for any carrier, data, roaming, or messaging charges from your
              provider. Messages may be delayed, dropped, filtered, or unavailable. You should not
              rely on the service for emergencies, time-critical instructions, safety-critical
              decisions, or legally required notices.
            </p>
          </section>

          <section>
            <h2>Consent to Messages</h2>
            <p>
              By texting the agent, you consent to receive conversational and service messages back
              from the agent at the number or chat identity you used. You can stop receiving
              messages by no longer texting the agent. If supported account controls are available,
              you may also unlink connected integrations from the web dashboard.
            </p>
          </section>

          <section>
            <h2>AI Output</h2>
            <p>
              The service uses AI models and connected tools that can make mistakes, omit relevant
              context, hallucinate facts, or produce outdated information. Output is provided for
              convenience only and is not professional, legal, medical, financial, tax, or safety
              advice. You are responsible for reviewing output before relying on it or sending it to
              anyone else.
            </p>
          </section>

          <section>
            <h2>Accounts and Integrations</h2>
            <p>
              You are responsible for your account, linked senders, connected calendars, Coder
              access, and any other integrations you enable. Only connect accounts you own or are
              authorized to use. Third-party services are governed by their own terms, privacy
              policies, availability, and fees.
            </p>
          </section>

          <section>
            <h2>Privacy and Content</h2>
            <p>
              Do not submit sensitive information unless you are comfortable with it being processed
              by the service and its connected providers. You retain responsibility for the content
              you send. You grant the service permission to process your messages, attachments,
              prompts, account metadata, and integration data as needed to operate, secure, debug,
              and improve the service. See the <a href="/privacy">Privacy Policy</a> for more
              detail.
            </p>
          </section>

          <section>
            <h2>U.S. Availability</h2>
            <p>
              The service is operated for users in the United States and is not directed to people
              in the European Economic Area, United Kingdom, or other jurisdictions where additional
              local compliance may be required. If you access the service from outside the United
              States, you do so on your own initiative and are responsible for complying with local
              law.
            </p>
          </section>

          <section>
            <h2>International Transfers</h2>
            <p>
              Messages, account data, attachments, prompts, logs, and integration data may be
              processed in the United States and by third-party providers in other countries. Those
              countries may not provide the same data protection rights as your home jurisdiction.
            </p>
          </section>

          <section>
            <h2>Paid Features and Refunds</h2>
            <p>
              Paid access, premium features, usage credits, subscriptions, or other fees are
              non-refundable except where required by law or expressly stated in writing. Features,
              prices, limits, models, and availability may change at any time.
            </p>
          </section>

          <section>
            <h2>No SLA</h2>
            <p>
              The service is provided without any service-level agreement, uptime commitment,
              support commitment, backup commitment, or guarantee that a message, response,
              integration action, or model result will complete successfully.
            </p>
          </section>

          <section>
            <h2>Beta and Changes</h2>
            <p>
              The service may include experimental, beta, preview, or third-party model features.
              Features, integrations, models, limits, prices, prompts, and availability may change,
              degrade, or be discontinued at any time.
            </p>
          </section>

          <section>
            <h2>Security</h2>
            <p>
              The service uses reasonable operational safeguards, but no internet-connected service
              can be guaranteed secure. You are responsible for protecting your devices, accounts,
              credentials, linked integrations, and message history.
            </p>
          </section>

          <section>
            <h2>Suspension and Termination</h2>
            <p>
              Access may be limited, suspended, or terminated at any time for abuse, security risk,
              nonpayment, suspected misuse, operational needs, legal compliance, or violation of
              these terms.
            </p>
          </section>

          <section>
            <h2>Export and Sanctions</h2>
            <p>
              You may not use the service if doing so is prohibited by applicable export control,
              sanctions, or trade restriction laws. You may not use the service to support activity
              involving embargoed regions, sanctioned parties, or prohibited end uses.
            </p>
          </section>

          <section>
            <h2>Indemnification</h2>
            <p>
              You agree to be responsible for claims, losses, liabilities, damages, costs, and
              expenses arising from your misuse of the service, violation of these terms, illegal
              content, spam, abuse, infringement, or use of connected third-party accounts without
              authorization.
            </p>
          </section>

          <section>
            <h2>Disclaimers</h2>
            <p>
              The service is provided "as is" and "as available" without warranties of any kind,
              including implied warranties of merchantability, fitness for a particular purpose,
              title, non-infringement, accuracy, availability, reliability, or error-free operation.
            </p>
          </section>

          <section>
            <h2>Limitation of Liability</h2>
            <p>
              To the fullest extent permitted by law, the service owner and operators will not be
              liable for indirect, incidental, special, consequential, exemplary, punitive, or lost
              profit damages, or for losses related to AI output, message delivery, account access,
              integrations, data loss, downtime, unauthorized access, or use of the service.
            </p>
          </section>

          <section>
            <h2>Governing Law</h2>
            <p>
              To the extent permitted by law, these terms are governed by the laws of California,
              without regard to conflict-of-law rules. Courts located in California will have
              exclusive jurisdiction over disputes that are not required to be handled elsewhere by
              applicable law.
            </p>
          </section>

          <section>
            <h2>Changes</h2>
            <p>
              These terms may be updated from time to time. Continued use of the service after an
              update means you accept the updated terms.
            </p>
          </section>
        </article>
      </main>
    </div>
  );
}
