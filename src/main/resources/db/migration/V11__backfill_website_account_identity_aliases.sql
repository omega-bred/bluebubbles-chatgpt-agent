WITH direct_links AS (
  SELECT
    l.account_base,
    trim(l.sender) AS sender,
    lower(trim(a.email)) AS account_email
  FROM website_account_sender_links l
  JOIN website_accounts a ON a.keycloak_subject = l.account_subject
  WHERE l.is_group = false
    AND l.account_base IS NOT NULL
    AND trim(l.account_base) <> ''
    AND a.email IS NOT NULL
    AND trim(a.email) <> ''
),
raw_aliases AS (
  SELECT account_base, sender AS identifier
  FROM direct_links
  WHERE sender IS NOT NULL AND sender <> ''
  UNION
  SELECT account_base, account_base AS identifier
  FROM direct_links
  UNION
  SELECT account_base, account_email AS identifier
  FROM direct_links
  WHERE account_email LIKE '%@%'
),
cleaned AS (
  SELECT
    account_base,
    identifier,
    regexp_replace(lower(trim(identifier)), '^(mailto:|tel:)', '') AS clean
  FROM raw_aliases
  WHERE identifier IS NOT NULL AND trim(identifier) <> ''
),
with_digits AS (
  SELECT
    account_base,
    identifier,
    clean,
    regexp_replace(clean, '[^0-9]+', '', 'g') AS digits
  FROM cleaned
),
normalized AS (
  SELECT
    account_base,
    identifier,
    CASE
      WHEN clean LIKE '%@%' THEN 'email'
      WHEN length(digits) >= 7 THEN 'phone'
      ELSE 'handle'
    END AS identifier_type,
    CASE
      WHEN clean LIKE '%@%' THEN regexp_replace(clean, '[[:space:]]+', '', 'g')
      WHEN length(digits) >= 7 AND length(digits) = 11 AND left(digits, 1) = '1'
        THEN substring(digits from 2)
      WHEN length(digits) >= 7 THEN digits
      ELSE regexp_replace(clean, '[[:space:]]+', '', 'g')
    END AS normalized_identifier
  FROM with_digits
),
deduped AS (
  SELECT DISTINCT ON (alias_key)
    alias_key,
    account_base,
    'bluebubbles' AS transport,
    identifier,
    identifier_type,
    normalized_identifier
  FROM (
    SELECT
      'bluebubbles:' || identifier_type || ':' || normalized_identifier AS alias_key,
      account_base,
      identifier,
      identifier_type,
      normalized_identifier
    FROM normalized
    WHERE normalized_identifier <> ''
  ) aliases
  ORDER BY alias_key, account_base
)
INSERT INTO agent_account_identity_aliases (
  alias_key,
  account_base,
  transport,
  identifier,
  identifier_type,
  normalized_identifier,
  created_at,
  updated_at
)
SELECT
  alias_key,
  account_base,
  transport,
  identifier,
  identifier_type,
  normalized_identifier,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
FROM deduped
ON CONFLICT (alias_key) DO UPDATE SET
  account_base = EXCLUDED.account_base,
  transport = EXCLUDED.transport,
  identifier = EXCLUDED.identifier,
  identifier_type = EXCLUDED.identifier_type,
  normalized_identifier = EXCLUDED.normalized_identifier,
  updated_at = EXCLUDED.updated_at;
