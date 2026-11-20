package io.breland.bbagent.server.agent;

import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public final class SharedAgentTestDatabase implements AutoCloseable {
  private final String schema = "agent_test_" + UUID.randomUUID().toString().replace("-", "");
  private final DriverManagerDataSource admin;
  private final String url;
  private final String user;
  private final String password;

  public SharedAgentTestDatabase() {
    String configured = System.getenv("BLUECHAT_TEST_POSTGRES_JDBC_URL");
    url =
        configured == null
            ? "jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            : configured;
    user = configured == null ? "sa" : System.getenv("BLUECHAT_TEST_POSTGRES_USER");
    password = configured == null ? "" : System.getenv("BLUECHAT_TEST_POSTGRES_PASSWORD");
    admin = new DriverManagerDataSource(url, user, password);
    new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V33__shared_agent_processing.sql"))
        .execute(connect());
  }

  public DriverManagerDataSource connect() {
    var dataSource = new DriverManagerDataSource(url, user, password);
    dataSource.setSchema(schema);
    return dataSource;
  }

  @Override
  public void close() {
    new JdbcTemplate(admin).execute("DROP SCHEMA " + schema + " CASCADE");
  }
}
