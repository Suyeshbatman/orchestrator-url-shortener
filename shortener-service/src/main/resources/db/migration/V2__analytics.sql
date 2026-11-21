-- Raw clicks, kept for drill-down and re-aggregation (in production: partitioned by month).
-- visitor_hash is a daily-salted HMAC of IP + user agent; the raw IP is never stored.
CREATE TABLE click_event (
    id            BIGSERIAL    PRIMARY KEY,
    link_id       BIGINT       NOT NULL REFERENCES link (id),
    occurred_at   TIMESTAMPTZ  NOT NULL,
    referrer_host VARCHAR(255),
    ua_family     VARCHAR(32)  NOT NULL,
    visitor_hash  CHAR(64)     NOT NULL
);

CREATE INDEX ix_click_event_link_occurred ON click_event (link_id, occurred_at);

-- Pre-aggregated per UTC day, upserted on write, so stats read ~90 rows instead of every event.
CREATE TABLE click_daily (
    link_id BIGINT NOT NULL REFERENCES link (id),
    day     DATE   NOT NULL,
    clicks  BIGINT NOT NULL,
    PRIMARY KEY (link_id, day)
);
