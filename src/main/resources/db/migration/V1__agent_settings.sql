CREATE TABLE assistant_responsiveness (
  chat_guid VARCHAR(255) PRIMARY KEY,
  responsiveness VARCHAR(64) NOT NULL
);

CREATE TABLE global_contact (
  sender VARCHAR(255) PRIMARY KEY,
  name VARCHAR(255) NOT NULL
);
