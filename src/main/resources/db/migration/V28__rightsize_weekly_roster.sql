-- =============================================
-- MEDICHANNEL
-- V28: Rightsize the published weekly roster
-- =============================================

-- The hospital has 40 doctors, so publishing 240-280 sessions every week is
-- not a realistic roster. This migration brings a full published week down to
-- 132 non-cancelled sessions:
--
--   * 28 doctors keep 3 sessions that week
--   * 12 doctors keep 4 sessions that week
--   * every retained session stays inside 07:00-11:00 or 13:00-15:00
--   * a doctor never has more than two sessions (60 places) in one day
--
-- Only editable future sessions are cancelled. Sessions that have already
-- finished and sessions that hold any appointment are never touched. The
-- dashboard continues to count real doctor_sessions rows, so this becomes a
-- real roster change rather than a display-only number.

CREATE TEMP TABLE weekly_roster_selection ON COMMIT DROP AS
WITH local_clock AS (
    SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Colombo') AS local_ts
),
local_day AS (
    SELECT local_ts, local_ts::date AS local_date
    FROM local_clock
),
doctor_order AS (
    SELECT
        d.id AS doctor_id,
        ROW_NUMBER() OVER (
            ORDER BY d.registration_number, d.id
        ) AS doctor_rank
    FROM doctors d
),
all_week_sessions AS (
    SELECT
        ds.doctor_id,
        date_trunc('week', ds.session_date)::date AS week_start,
        (ds.session_date + ds.end_time) <= lc.local_ts AS finished
    FROM doctor_sessions ds
    CROSS JOIN local_clock lc
    WHERE ds.status <> 'CANCELLED'
),
week_past AS (
    SELECT doctor_id, week_start, COUNT(*) AS past_count
    FROM all_week_sessions
    WHERE finished
    GROUP BY doctor_id, week_start
),
editable_candidates AS (
    SELECT
        ds.id,
        ds.doctor_id,
        ds.session_date,
        ds.start_time,
        ds.end_time,
        date_trunc('week', ds.session_date)::date AS week_start,
        d.doctor_rank,
        CASE WHEN d.doctor_rank <= 12 THEN 4 ELSE 3 END AS week_target,
        COALESCE(p.past_count, 0) AS past_count,
        EXISTS (
            SELECT 1
            FROM appointments a
            WHERE a.session_id = ds.id
        ) AS has_appointment
    FROM doctor_sessions ds
    JOIN doctor_order d ON d.doctor_id = ds.doctor_id
    CROSS JOIN local_day ld
    LEFT JOIN week_past p
      ON p.doctor_id = ds.doctor_id
     AND p.week_start = date_trunc('week', ds.session_date)::date
    WHERE ds.status <> 'CANCELLED'
      AND ds.session_date >= ld.local_date
      AND (ds.session_date + ds.end_time) > ld.local_ts
      AND (
            (ds.start_time = TIME '07:00' AND ds.end_time = TIME '11:00')
         OR (ds.start_time = TIME '13:00' AND ds.end_time = TIME '15:00')
      )
),
candidate_counts AS (
    SELECT
        doctor_id,
        week_start,
        COUNT(*) AS candidate_count,
        COUNT(*) FILTER (WHERE has_appointment) AS appointment_count,
        MIN(GREATEST(week_target - past_count, 0)) AS future_keep_target
    FROM editable_candidates
    GROUP BY doctor_id, week_start
),
ranked_candidates AS (
    SELECT
        c.*,
        cc.candidate_count,
        cc.appointment_count,
        cc.future_keep_target,
        ROW_NUMBER() OVER (
            PARTITION BY c.doctor_id, c.week_start, c.has_appointment
            ORDER BY
                c.session_date,
                CASE WHEN c.doctor_rank % 2 = 0
                     THEN c.start_time END ASC,
                CASE WHEN c.doctor_rank % 2 = 1
                     THEN c.start_time END DESC,
                c.id
        ) AS non_appointment_rank,
        COUNT(*) FILTER (WHERE NOT c.has_appointment) OVER (
            PARTITION BY c.doctor_id, c.week_start
        ) AS non_appointment_count
    FROM editable_candidates c
    JOIN candidate_counts cc
      ON cc.doctor_id = c.doctor_id
     AND cc.week_start = c.week_start
),
stratified_candidates AS (
    SELECT
        r.*,
        GREATEST(
            r.future_keep_target - r.appointment_count,
            0
        ) AS non_appointment_keep,
        CASE
            WHEN r.has_appointment OR r.non_appointment_count = 0
            THEN NULL
            ELSE FLOOR(
                (r.non_appointment_rank - 1)
                * GREATEST(
                    r.future_keep_target - r.appointment_count,
                    0
                )::numeric
                / r.non_appointment_count
            )::integer
        END AS selection_bucket
    FROM ranked_candidates r
),
bucketed_candidates AS (
    SELECT
        s.*,
        ROW_NUMBER() OVER (
            PARTITION BY s.doctor_id, s.week_start, s.selection_bucket
            ORDER BY s.non_appointment_rank
        ) AS bucket_rank
    FROM stratified_candidates s
)
SELECT
    id,
    has_appointment
        OR (
            non_appointment_keep > 0
            AND selection_bucket IS NOT NULL
            AND selection_bucket < non_appointment_keep
            AND bucket_rank = 1
        ) AS keep_session
FROM bucketed_candidates;

-- A session that holds any appointment is always kept, even if that makes a
-- particular doctor's week temporarily larger than the nominal target. This
-- is safer than silently cancelling a patient's booking during a data fix.
UPDATE doctor_sessions ds
SET status = 'CANCELLED',
    updated_at = CURRENT_TIMESTAMP
FROM weekly_roster_selection s
WHERE s.id = ds.id
  AND NOT s.keep_session
  AND ds.status <> 'CANCELLED'
  AND NOT EXISTS (
      SELECT 1
      FROM appointments a
      WHERE a.session_id = ds.id
  );

-- =============================================
-- END OF V28
-- =============================================
