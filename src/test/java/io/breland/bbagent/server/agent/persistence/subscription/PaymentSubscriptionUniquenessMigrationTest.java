package io.breland.bbagent.server.agent.persistence.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PaymentSubscriptionUniquenessMigrationTest {

  @Test
  void migrationReconcilesDuplicatesBeforeEnforcingUniqueness() throws Exception {
    String databaseName = "subscription-migration-" + UUID.randomUUID().toString().replace("-", "");
    String jdbcUrl = "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    migrate(jdbcUrl, "30");

    try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
      seedDuplicateSubscriptions(connection);
    }

    migrate(jdbcUrl, "31");

    try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
      assertThat(queryLong(connection, "SELECT COUNT(*) FROM payment_subscriptions")).isOne();
      assertThat(
              queryString(
                  connection,
                  "SELECT subscription_id FROM payment_subscriptions WHERE provider = 'stripe'"))
          .isEqualTo("subscription-newer");
      assertThat(
              queryLong(
                  connection,
                  "SELECT COUNT(*) FROM payment_provider_events WHERE subscription_id = 'subscription-newer'"))
          .isEqualTo(2);
      assertThatThrownBy(() -> insertConflictingSubscription(connection))
          .isInstanceOf(SQLException.class);
    }
  }

  private static void migrate(String jdbcUrl, String target) {
    Flyway.configure()
        .dataSource(jdbcUrl, "sa", "")
        .locations("classpath:db/migration")
        .target(target)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static void seedDuplicateSubscriptions(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO agent_accounts (account_id, created_at, updated_at)
          VALUES ('account-1', TIMESTAMP '2026-05-31 03:00:00', TIMESTAMP '2026-05-31 03:00:00')
          """);
      statement.executeUpdate(
          """
          INSERT INTO payment_checkout_sessions
            (checkout_session_id, account_id, provider, plan_key, status, created_at, updated_at)
          VALUES
            ('checkout-1', 'account-1', 'stripe', 'premium_monthly', 'completed',
             TIMESTAMP '2026-05-31 03:00:00', TIMESTAMP '2026-05-31 03:00:00')
          """);
      statement.executeUpdate(
          subscriptionInsert("subscription-older", "2026-05-31 03:09:40", "2026-06-02 15:59:25"));
      statement.executeUpdate(
          subscriptionInsert("subscription-newer", "2026-05-31 03:09:40", "2026-06-02 15:59:28"));
      statement.executeUpdate(providerEventInsert("event-checkout", "subscription-older"));
      statement.executeUpdate(providerEventInsert("event-subscription", "subscription-newer"));
    }
  }

  private static String subscriptionInsert(
      String subscriptionId, String createdAt, String updatedAt) {
    return """
        INSERT INTO payment_subscriptions
          (subscription_id, account_id, provider, plan_key, provider_subscription_id,
           provider_customer_id, provider_customer_selector, status, checkout_session_id,
           raw_payload, created_at, updated_at)
        VALUES
          ('%s', 'account-1', 'stripe', 'premium_monthly', 'provider-subscription-1',
           'customer-1', 'customer-1', 'trialing', 'checkout-1', '{}',
           TIMESTAMP '%s', TIMESTAMP '%s')
        """
        .formatted(subscriptionId, createdAt, updatedAt);
  }

  private static String providerEventInsert(String eventId, String subscriptionId) {
    return """
        INSERT INTO payment_provider_events
          (event_id, provider, provider_event_id, event_type, account_id, checkout_session_id,
           subscription_id, provider_subscription_id, status, raw_payload, received_at,
           processed_at)
        VALUES
          ('%s', 'stripe', 'provider-%s', 'subscription-event', 'account-1', 'checkout-1',
           '%s', 'provider-subscription-1', 'processed', '{}',
           TIMESTAMP '2026-05-31 03:09:40', TIMESTAMP '2026-05-31 03:09:41')
        """
        .formatted(eventId, eventId, subscriptionId);
  }

  private static void insertConflictingSubscription(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          subscriptionInsert(
              "subscription-conflict", "2026-06-03 00:00:00", "2026-06-03 00:00:00"));
    }
  }

  private static long queryLong(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static String queryString(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getString(1);
    }
  }
}
