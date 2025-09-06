-- Seed roles
INSERT INTO roles(code) VALUES
 ('OWNER'),('GLOBAL_ADMIN'),('HEAD_MANAGER'),('CLUB_ADMIN'),('MANAGER'),('ENTRY_MANAGER'),('PROMOTER'),('GUEST');

-- Seed clubs
INSERT INTO clubs(name, description, timezone, admin_channel_id)
VALUES
 ('Aurora','Moscow main club','Europe/Moscow',NULL),
 ('Nebula','Berlin vibes','Europe/Berlin',NULL),
 ('Eclipse','NYC nights','America/New_York',NULL),
 ('Mirage','Tokyo lounge','Asia/Tokyo',NULL);

-- Seed zones
INSERT INTO zones(club_id, name, priority) VALUES
 (1,'Main Hall',1),
 (2,'Main Hall',1),
 (3,'Main Hall',1),
 (4,'Main Hall',1);

-- Seed tables
INSERT INTO tables(club_id, zone_id, table_number, capacity, min_deposit) VALUES
 (1,1,1,4,100),
 (1,1,2,4,100),
 (1,1,3,4,100),
 (2,2,1,4,80),
 (2,2,2,4,80),
 (2,2,3,4,80),
 (3,3,1,4,120),
 (3,3,2,4,120),
 (3,3,3,4,120),
 (4,4,1,4,90),
 (4,4,2,4,90),
 (4,4,3,4,90);

-- Seed promoter user and role
WITH u AS (
    INSERT INTO users(telegram_user_id, username, display_name)
    VALUES (1000001, 'promoter1', 'Promoter One')
    RETURNING id
)
INSERT INTO user_roles(user_id, role_code, scope_type, scope_club_id)
SELECT u.id, 'PROMOTER', 'CLUB', 1 FROM u;

-- Seed events for upcoming Saturday 22:00-06:00 local time
WITH c AS (
    SELECT id, timezone FROM clubs
)
INSERT INTO events(club_id, title, start_at, end_at)
SELECT c.id,
       'Regular Night',
       ((date_trunc('week', now() AT TIME ZONE c.timezone) + interval '5 days' + time '22:00') AT TIME ZONE c.timezone) AT TIME ZONE 'UTC',
       ((date_trunc('week', now() AT TIME ZONE c.timezone) + interval '6 days' + time '06:00') AT TIME ZONE c.timezone) AT TIME ZONE 'UTC'
FROM c;
