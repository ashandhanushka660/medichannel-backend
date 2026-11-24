-- Administrator and manager account creation must not leave staff blocked
-- behind a forced password-change screen. Clear legacy flags so existing
-- accounts can sign in normally with their current password.
UPDATE users
SET password_change_required = FALSE
WHERE password_change_required = TRUE;
