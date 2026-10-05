package io.breland.bbagent.server.agent.persistence.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class H2MigrationCompatibilityTest {
  @Test
  void legacyAliasBackfillInsertsNormalizedAliasesAndUpdatesExistingAliases() throws Exception {
    String jdbcUrl =
        "jdbc:h2:mem:aliases-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    migrate(jdbcUrl, "10");
    try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO website_accounts (keycloak_subject, email, created_at, updated_at)
          VALUES ('subject', ' Person@Example.com ', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """);
      statement.executeUpdate(
          """
          INSERT INTO website_account_sender_links
            (link_id, account_subject, account_base, coder_account_base, gcal_account_base,
             sender, is_group, created_at, updated_at)
          VALUES ('link', 'subject', 'account', 'account', 'account', 'tel:+1 (415) 555-1234',
                  false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """);
      statement.executeUpdate(
          """
          INSERT INTO agent_account_identity_aliases
            (alias_key, account_base, transport, identifier, identifier_type,
             normalized_identifier, created_at, updated_at)
          VALUES ('bluebubbles:phone:4155551234', 'old-account', 'bluebubbles', 'old', 'phone',
                  '4155551234', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """);
    }
    migrate(jdbcUrl, "11");
    try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
        var statement = connection.createStatement();
        var aliases =
            statement.executeQuery(
                "SELECT alias_key, account_base FROM agent_account_identity_aliases ORDER BY alias_key")) {
      var keys = new java.util.ArrayList<String>();
      while (aliases.next()) {
        keys.add(aliases.getString("alias_key"));
        assertThat(aliases.getString("account_base")).isEqualTo("account");
      }
      assertThat(keys)
          .containsExactly(
              "bluebubbles:email:person@example.com",
              "bluebubbles:handle:account",
              "bluebubbles:phone:4155551234");
    }
  }

  private static void migrate(String jdbcUrl, String target) {
    Flyway.configure()
        .dataSource(jdbcUrl, "sa", "")
        .locations("classpath:db/h2-migration")
        .target(target)
        .load()
        .migrate();
  }
}
