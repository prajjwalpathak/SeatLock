-- V5__demo_users.sql: public demo logins (W2·D2). The password is PUBLIC on purpose: it will be
-- shown on the login page so anyone (e.g. an interviewer) can try the app. Both accounts are
-- low-privilege. ADMIN is never seeded here; it is created at startup from environment
-- variables (identity.application.AdminBootstrap), so admin credentials are never in Git.
--
-- Demo password for both: seatlock-demo
-- Hash: BCrypt cost 10, with Spring's {bcrypt} prefix (DelegatingPasswordEncoder).

INSERT INTO users (email, password_hash, display_name, role, auth_provider)
VALUES ('demo.user@seatlock.test',
        '{bcrypt}$2a$10$l1.WPHEu5RT72FOS5rX92.pXbK9ZgDpB4rA0Qb/DDYFapZAthom5C',
        'Demo User', 'USER', 'LOCAL');

-- The demo organizer is an existing seed organizer, so it already owns events and shows.
-- Unlocks it ('!' → demo hash); the other two seed organizers stay locked.
UPDATE users
SET password_hash = '{bcrypt}$2a$10$l1.WPHEu5RT72FOS5rX92.pXbK9ZgDpB4rA0Qb/DDYFapZAthom5C',
    updated_at    = now()
WHERE email = 'organizer.cinema@seatlock.test';
