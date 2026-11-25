# Brief-aligned weekly roster seeding

The assignment brief states that Lanka Care Hospital **runs more than 120
channelling sessions a week**. With 40 doctors, the production roster is
planned at **132 sessions in a full week**: 28 doctors have three sessions
and 12 doctors have four. The `sessionsThisWeek` field in
`/api/v1/reports/overview` is a real database aggregate over the current
Asia/Colombo Monday–Sunday week; it is never hardcoded.

## Controlled production run

`BriefRosterSeeder` is disabled by default. Enable it for exactly one Heroku
release, then disable it again:

```bash
heroku config:set APP_BRIEF_ROSTER_SEED=true APP_BRIEF_ROSTER_WEEKS=2 --app evening-plains-20028
git push heroku main
heroku logs --tail --app evening-plains-20028 | grep -i "Brief roster"
heroku config:unset APP_BRIEF_ROSTER_SEED APP_BRIEF_ROSTER_WEEKS --app evening-plains-20028
```

The default horizon is two weeks. This is enough to satisfy the briefing
statistic without holding a database transaction open for the six-month demo
horizon. Each service call commits independently, so the run is resumable.

`APP_DEMO_PASSWORD` must already be set. The runner reuses deterministic demo
doctor identities, creates only the missing specialisations/doctors/sessions,
and does **not** create patients, notices, historical appointments, or cancel
anything.

The application pins the JVM default timezone to UTC (`Procfile` and
`MedichannelApplication`) while all business dates use the Asia/Colombo `Clock`
bean. This keeps `LocalTime` session columns exactly at `07:00–11:00` and
`13:00–15:00` regardless of the host timezone.

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
**132 non-cancelled sessions in a full published week**. The current week can
legitimately be lower when past slots are skipped or higher while an old
roster is being retired; the Flyway V28 migration records the production
rightsizing.

The dashboard count itself is still the real current-week aggregate. It is
never replaced by a hardcoded 120, 121, 132, or any other figure.

The production result must be recorded in a separate evidence file with the
deployment timestamp; do not edit historical audit or test reports.
