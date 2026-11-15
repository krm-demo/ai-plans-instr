# How to deploy (one time setup, then one click)

You do these steps once. After that, every GitHub release deploys everything.

## A. Maven Central

1. Open https://central.sonatype.com and sign in with GitHub.
2. Open **Namespaces**. You should see `io.github.<your-github-name>`. Use this as `groupId`.
3. Open your account menu and click **Generate User Token**. Save the username and the password.
4. Make a signing key on your computer:
   ```
   gpg --full-generate-key
   gpg --list-secret-keys --keyid-format long      # copy the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>       # copy the whole text block
   ```

## B. AWS

5. Sign in to the AWS console. Choose your region (top right).
6. Open **CloudFormation > Create stack > With new resources**.
7. Upload the file `infra/github-oidc.yaml`. Set `GitHubRepo` to `yourname/plans`.
8. Tick the box that says IAM resources will be created. Click **Create stack**.
9. When it is done, open the **Outputs** tab. Copy `DeployRoleArn`.

## C. GitHub (repository > Settings > Secrets and variables > Actions)

10. Add these **secrets**:
    - `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` (from step 3)
    - `GPG_PRIVATE_KEY` (the text block from step 4)
    - `GPG_PASSPHRASE` (the passphrase of your key)
    - `AWS_ROLE_ARN` (from step 9)
11. Add this **variable**: `AWS_REGION`, for example `us-east-1`.
12. In `pom.xml`, replace `io.github.yourname`, `yourname`, `Your Name` and the license with your own values. Do the same in `plans-core/pom.xml` and `plans-lambda/pom.xml` for the `groupId`.

## D. Release

13. On GitHub open **Releases > Draft a new release**. Make a new tag, for example `v0.1.0`. Click **Publish release**.
14. Open the **Actions** tab and watch the run. It builds, uploads to Central, deploys to AWS and updates the database.
15. Open https://central.sonatype.com/publishing/deployments. Check the upload. Click **Publish**. (This is manual on purpose for the first release.)

## E. Test it

16. In the AWS console open **CloudFormation > stack `plans` > Outputs**. Copy `ApiUrl`.
17. Every call must be signed with your AWS credentials. Use `awscurl`:
    ```
    pip install awscurl
    awscurl --service execute-api --region us-east-1 -X POST \
      -H "Content-Type: application/json" -H "User-Agent: my-first-test/1.0" \
      -d '{"planType":"RECURRING_INVESTMENT","planStatus":"ACTIVE","userId":"u1",
           "ddaAccInfo":{"ddaAccNum":"123","ddaBankId":"B1"},
           "invAccInfo":{"invAccNum":"456","occdCode":"X"},"currency":"USD"}' \
      <ApiUrl>/plans/save
    ```
18. The answer must contain `restInfo` with your user agent, the time and the build number.
    If `userAgent` is `null`, tell me. It means the HTTP headers did not reach the function.

## Good to know

- The database costs money while it runs. To stop it, delete the stack `plans` in CloudFormation. It makes a final snapshot first.
- If step 8 says "already exists", the account already has the GitHub identity provider. Create the stack again with `CreateOidcProvider` = `false`.
- If the `plans` stack says the Postgres version was not found, run `aws rds describe-db-engine-versions --engine postgres` and set `PostgresVersion` in `infra/template.yaml`.
- The Lambdas have no internet access. They only talk to the database.
