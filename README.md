# MediChannel – Backend

## Project Overview

MediChannel is a Hospital Channelling and Appointment Management System developed as a group project for IT404050 – Full Stack Development.

The backend provides REST APIs, authentication, business logic, database operations, and real-time communication.

## Technology Stack

- Java
- Spring Boot
- Spring Security
- Spring Data JPA
- Spring WebSocket
- PostgreSQL
- Maven

## Deployment Architecture

- Backend hosting: Heroku
- Database hosting: Supabase PostgreSQL
- Frontend hosting: Vercel

## User Roles

- CUSTOMER
- DOCTOR
- MANAGER
- ADMIN

## Main Modules

- Authentication and authorization
- User and role management
- Patient management
- Doctor and specialisation management
- Room and session management
- Appointment booking
- Queue management
- Online consultation chat
- Notifications
- Feedback and notices
- Audit logs

## Database

The system uses PostgreSQL hosted on Supabase.

Database schema changes will be managed through Flyway migration files.

Never commit database passwords, connection strings, encryption keys, JWT secrets, or other credentials.

## Getting Started

### Run locally

1. Copy `.env.example` to `.env` and set the Supabase Session pooler JDBC host,
   database role, password, and three distinct Base64-encoded 32-byte secrets.
   Do not commit `.env`.
2. Start the backend with the `dev` profile:

       SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

The backend listens on port 8080 by default. Flyway applies pending migrations
at application startup, so use a disposable Supabase project or a local
PostgreSQL database for testing. Do not point a first deployment at
`production` until reviewing and validating the pending migrations.

## Environment Variables

Create a local `.env` file from `.env.example` for development.

Example:

    DB_URL=jdbc:postgresql://YOUR_NEON_HOST/YOUR_DATABASE?sslmode=require
    DB_USERNAME=YOUR_DATABASE_USERNAME
    DB_PASSWORD=YOUR_DATABASE_PASSWORD

These are example placeholders only. Use your own development credentials.

Do not commit `.env` or any file containing real credentials.

## Deploy to Heroku

The repository includes `system.properties` (Java 21) and a `Procfile` web
process. Create the app, set the config vars, then push:

    heroku create <app-name>
    heroku config:set -a <app-name> \
      DB_URL='jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require' \
      DB_USERNAME='postgres.<project-ref>' \
      DB_PASSWORD='...' \
      JWT_SECRET='...' ENCRYPTION_KEY='...' HMAC_SECRET='...' \
      APP_FRONTEND_ORIGIN='https://<your-vercel-domain>' \
      SPRING_PROFILES_ACTIVE=prod
    git push heroku main

Provide these config vars:

- `DB_URL`: JDBC URL for Supabase, using the **Session pooler** host and
  `sslmode=require`. The direct `db.<ref>.supabase.co` host is IPv6-only and
  Heroku cannot reach it. `DB_USERNAME` is `postgres.<project-ref>`.
- `DB_USERNAME` and `DB_PASSWORD`: Supabase credentials allowed to run the
  Flyway migrations and serve the application.
- `JWT_SECRET`, `ENCRYPTION_KEY`, and `HMAC_SECRET`: three separate
  Base64-encoded 32-byte secrets. Generate each independently with
  `openssl rand -base64 32`. `ENCRYPTION_KEY` encrypts patient identifiers at
  rest and must never be changed once data exists.
- `APP_FRONTEND_ORIGIN`: exact deployed frontend origin, without a path or
  wildcard (for example `https://medichannel-f-p3z5.vercel.app`).
- `APP_ADMIN_EMAIL` and `APP_ADMIN_PASSWORD`: used once to bootstrap the first
  ADMIN account. The password must contain at least 8 characters, including a
  letter, a number and a symbol. Remove or rotate these values after the first
  successful sign-in.
- `APP_DEMO_SEED`: leave unset or `false` in production. Set `true` only on an
  isolated staging database and also provide `APP_DEMO_PASSWORD` (8+ characters
  with a letter, a number and a symbol).

Heroku supplies `PORT`; Spring Boot binds to it automatically. Flyway runs
every unapplied migration included in the deployed artifact (currently V1–V25),
so verify all migration files are committed and test them against a disposable
database before deploying. Check `/actuator/health` after the first deploy.

## Team Development

1. Clone the repository.
2. Create a feature branch for your assigned task.
3. Make your changes.
4. Commit and push your work if you have repository write access.
5. Submit a pull request for review.

## Project Repository

https://github.com/wcm24b126-hue/medichannel-backend
