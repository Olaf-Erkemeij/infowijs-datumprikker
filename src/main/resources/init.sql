CREATE TABLE IF NOT EXISTS polls (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_by VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS poll_options (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id uuid REFERENCES polls(id),
    begin_date_time TIMESTAMPTZ,
    end_date_time TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id uuid REFERENCES polls(id),
    poll_option_id uuid UNIQUE REFERENCES poll_options(id),
    booked_by VARCHAR(100) NOT NULL
);
