# Brief-aligned weekly roster seeding

The assignment brief states that Lanka Care Hospital **runs more than 120
channelling sessions a week**. The `sessionsThisWeek` field in
`/api/v1/reports/overview` is a real database aggregate over the current
Asia/Colombo Monday–Sunday week; it is never hardcoded.

## Controlled production run

`BriefRosterSeeder` is disabled by default. Enable it for exactly one Heroku
release, then disable it again:

```bash
heroku config:set APP_BRIEF_ROSTER_SEED=true --app evening-plains-20028
git push heroku main
heroku logs --tail --app evening-plains-20028 | grep -i "Brief roster"
heroku config:unset APP_BRIEF_ROSTER_SEED --app evening-plains-20028
```

`APP_DEMO_PASSWORD` must already be set. The runner reuses deterministic demo
doctor identities, creates only the missing specialisations/doctors/sessions,
and does **not** create patients, notices, historical appointments, or cancel
anything.

The application pins the JVM default timezone to UTC (`Procfile` and
`MedichannelApplication`) while all business dates use the Asia/Colombo `Clock`
bean. This keeps `LocalTime` session columns exactly at `07:00–09:30` and
`12:00–14:30` regardless of the host timezone.

## Verification queries

```sql
SELECT count(*) FROM specialisations;
SELECT count(*) FROM doctors d
JOIN users u ON u.id = d.user_id
JOIN specialisations s ON s.id = d.specialisation_id
WHERE d.available = true AND s.active = true
  AND u.account_status = 'ACTIVE' AND u.email_verified = true;

SELECT count(*)
FROM doctor_sessions
WHERE session_date BETWEEN
        (CURRENT_DATE - ((EXTRACT(ISODOW FROM CURRENT_DATE)::int - 1)))
    AND (CURRENT_DATE - ((EXTRACT(ISODOW FROM CURRENT_DATE)::int - 1)) + 6)
  AND status <> 'CANCELLED';
```

Expected after seeding: **15 specialisations**, **40 eligible doctors**, and
**more than 120 non-cancelled sessions in the current week** (280 in a full
published week).

The production result must be recorded in a separate evidence file with the
deployment timestamp; do not edit historical audit or test reports.
