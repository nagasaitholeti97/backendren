# SAMS backend: deploy on Render (Docker) with Supabase

1. Put THIS folder's contents in a GitHub repository (pom.xml and Dockerfile at the top level).
   run-supabase.ps1 is in .gitignore on purpose (it is only for running on your own PC).
2. Render -> New + -> Web Service -> pick the repository -> Language: Docker -> Free.
3. Environment variables (Render dashboard, never in code):

   DB_URL            jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?sslmode=require
   DB_USER           postgres.<your-project-ref>
   DB_PASSWORD       <your Supabase database password, letters and numbers only>
   SEED_DEMO_DATA    false        (set true only on the very first run against an empty database)
   SAMS_JWT_SECRET   <32 or more random characters>
   SAMS_CORS_ORIGINS https://<your-app>.vercel.app      (after the frontend is deployed)
   SAMS_FRONTEND_URL https://<your-app>.vercel.app      (after the frontend is deployed)
   Optional email (SMTP): MAIL_ENABLED=true MAIL_HOST MAIL_PORT MAIL_USER MAIL_PASSWORD MAIL_FROM
   (Render's free plan blocks outgoing SMTP ports; use a paid instance or an email API.)

4. Deploy. It is ready when the log shows "Started SamsApplication". Test:
   https://<your-service>.onrender.com/api/reference

If the connection to Supabase fails, try DB_URL on port 6543 with &prepareThreshold=0 (Transaction pooler),
and check Supabase -> Database -> Settings -> Network Bans.
