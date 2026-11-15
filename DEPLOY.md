# How to set everything up (one time), then every push to main builds and deploys

You do these steps once. After that:

- **every push to `main`** builds, runs the tests, publishes the report site and deploys to AWS (`ci.yml`);
- **every GitHub release** uploads the library to Maven Central (`release.yml`).

Never paste a password, token or key into a chat. Put them only into the GitHub secrets below.

## A. GitHub: report site (2 minutes, free)

1. Repository **Settings > Pages**. Under **Source** choose **GitHub Actions**.
2. Push to `main` (or merge a pull request). The badges in `README.md` start to work after the first green run.

## B. Maven Central

3. Sign in at https://central.sonatype.com with GitHub. Under **Namespaces** you should see `io.github.krm-demo`.
4. Account menu > **Generate User Token**. Save the username and the password of the token.
5. Make a **new** signing key. Do not reuse a key that was ever written into a file in a repository.
   ```
   gpg --full-generate-key
   gpg --list-secret-keys --keyid-format long      # copy the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```
6. In the repository open **Settings > Secrets and variables > Actions** and add these **secrets**
   (with the GitHub CLI each command asks for the value, so it does not stay in your shell history):
   ```
   gh secret set CENTRAL_USERNAME --repo krm-demo/ai-plans-instr
   gh secret set CENTRAL_PASSWORD --repo krm-demo/ai-plans-instr
   gh secret set GPG_PASSPHRASE   --repo krm-demo/ai-plans-instr
   gpg --armor --export-secret-keys <KEY_ID> | gh secret set GPG_PRIVATE_KEY --repo krm-demo/ai-plans-instr
   ```

## C. AWS (optional, costs money while the database runs, see below)

7. Sign in to the AWS console and choose your region (top right).
8. **CloudFormation > Create stack > With new resources**. Upload `infra/github-oidc.yaml`.
   Set `GitHubRepo` to `krm-demo/ai-plans-instr`. Tick the box that says IAM resources will be created.
   (If the stack says the provider "already exists", create it again with `CreateOidcProvider` = `false`.)
9. When it is done, open the **Outputs** tab and copy `DeployRoleArn`.
10. In GitHub add the **secret** `AWS_ROLE_ARN` (the value from step 9) and these **variables**:
    - `AWS_REGION`, for example `us-east-1`
    - `AWS_DEPLOY` = `true` (this switches the deployment on; delete it or set `false` to switch it off)
11. Set a monthly budget of about $10 in the AWS **Budgets** service before the first deployment.

The role can only be used by pushes to the `main` branch of this repository.

## D. Release to Maven Central

12. On GitHub: **Releases > Draft a new release**. Create the tag, for example `v0.1.0`, and click **Publish release**.
13. Watch **Actions > release**. It builds, tests and uploads the library (jar, sources, javadoc, signatures).
14. Open https://central.sonatype.com/publishing/deployments, check the upload and click **Publish**.
    (The upload does not publish by itself on purpose. Published versions can never be changed or removed.)

## E. Try the deployed functions

15. **CloudFormation > stack `plans` > Outputs** shows `ApiUrl`. Every call must be signed with AWS credentials:
    ```
    pip install awscurl
    awscurl --service execute-api --region us-east-1 -X POST \
      -H "Content-Type: application/json" -H "User-Agent: my-first-test/1.0" \
      -d '{"planType":"RECURRING_INVESTMENT","planStatus":"ACTIVE","userId":"u1",
           "ddaAccInfo":{"ddaAccNum":"123","ddaBankId":"B1"},
           "invAccInfo":{"invAccNum":"456","occdCode":"X"},"currency":"USD"}' \
      <ApiUrl>/plans/save
    ```
16. The answer must contain `restInfo` with your user agent, the time and the build number.
    If `userAgent` is `null`, the HTTP headers did not reach the function.

## Good to know

- Database cost: about $15 a month when it runs all month (details in the chat history). Delete the stack
  `plans` in CloudFormation to stop it; it keeps a final snapshot, delete that too when you do not need it.
- If the stack says the Postgres version was not found, run `aws rds describe-db-engine-versions --engine postgres`
  and set `PostgresVersion` in `infra/template.yaml`.
- The Lambdas have no internet access. They only talk to the database.
- Versions are simple: `0.1.0-SNAPSHOT` in the poms, and a release `vX.Y.Z` sets the version from its tag.
