# Campus Lost & Found (Java + Supabase + Render)

A Java web server (JDK only, no libraries) that stores posts, claims, suggestions and photos in Supabase.

## Files
- `LostFoundServer.java` - the Java backend
- `public/index.html` - the website page
- `supabase.sql` - creates the database tables and the photo bucket
- `Dockerfile` and `render.yaml` - let Render run the project

## Step 1: Set up Supabase
1. Go to supabase.com, sign up, and create a new project.
2. Open **SQL Editor**, choose **New query**, paste everything from `supabase.sql`, and press **Run**.
3. Open **Project Settings > API**. Copy two things:
   - **Project URL** (looks like https://abcdxyz.supabase.co)
   - **service_role** key (the secret one, NOT the anon key)

   Never put the service_role key in the HTML page or in your GitHub code.

## Step 2: Upload to GitHub
Create a new repository and upload all files from this folder (keep the `public` folder).

## Step 3: Deploy on Render
1. Go to render.com and sign in with GitHub.
2. Choose **New > Web Service** and pick your repository. Render finds the Dockerfile.
3. Under **Environment**, add:
   - `SUPABASE_URL` = your Project URL
   - `SUPABASE_KEY` = your service_role key
   - `ADMIN_KEY` = a host password of your own (opens the Suggestion box inbox and lets you delete any post)
4. Deploy. Render gives you a public link to share with students.

## Run on your own PC (optional)
Install JDK 11 or newer, then in this folder (Windows PowerShell shown):

    $env:SUPABASE_URL="https://abcdxyz.supabase.co"
    $env:SUPABASE_KEY="your-service-role-key"
    $env:ADMIN_KEY="your-password"
    java LostFoundServer.java

On Mac or Linux use `export NAME=value` instead. Then open http://localhost:8080

## Notes
- Render's free plan puts the site to sleep after about 15 minutes without visitors, so the first visit afterwards takes around a minute.
- Posts and photos are kept in Supabase, so they survive restarts and redeploys.
- Contact details are only sent to the person who posted the item, and to the host.
