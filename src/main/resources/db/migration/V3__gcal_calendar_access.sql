CREATE TABLE gcal_calendar_access (
  id VARCHAR(512) PRIMARY KEY,
  account_key VARCHAR(255) NOT NULL,
  calendar_id VARCHAR(255) NOT NULL,
  mode VARCHAR(32) NOT NULL
);

CREATE INDEX idx_gcal_calendar_access_account ON gcal_calendar_access (account_key);
CREATE INDEX idx_gcal_calendar_access_calendar ON gcal_calendar_access (calendar_id);
