create table if not exists payments (
    id uuid primary key default gen_random_uuid(),
    booking_id uuid null,
    provider text not null,
    currency varchar(8) not null,
    amount_minor bigint not null,
    status text not null check (status in ('INITIATED','PENDING','CAPTURED','REFUNDED','DECLINED')),
    payload text not null unique,
    external_id text null,
    idempotency_key text not null unique,
    created_at timestamptz default now(),
    updated_at timestamptz default now()
);
