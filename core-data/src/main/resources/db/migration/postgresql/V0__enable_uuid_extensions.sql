-- Enable pgcrypto for gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Fallback: ensure gen_random_uuid() is available via uuid-ossp if pgcrypto is not present.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'gen_random_uuid') THEN
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
    CREATE OR REPLACE FUNCTION gen_random_uuid()
    RETURNS uuid
    LANGUAGE SQL
    IMMUTABLE
    AS $$ SELECT uuid_generate_v4(); $$;
  END IF;
END
$$;
